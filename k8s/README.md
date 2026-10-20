# Running the backend on Kubernetes

Manifests for running the backend on a local cluster, such as the one built into Docker Desktop, with any number of replicas. The database stays outside the cluster: the Docker Compose Postgres on your machine (`compose.yaml`).

## How sessions stay on one pod

A game session lives in the memory of the pod that created it. Every request about a session must therefore reach that pod, whichever of the replicas the load balancer would otherwise pick.

- **Clients send the session id as `?session=<id>`** on every request about a session: creating it (`PUT /api/game-sessions/{id}`, with an id the client chose), fetching and joining it, and the `/ws` connection (`docs/api.md` §3.2). A query parameter is used because a load balancer can't read part of a path, and browsers can't add headers to a WebSocket connection.
- **The `backend-sessions` Ingress hashes that parameter to pick the pod** (`upstream-hash-by: "$arg_session"`), so the same id always reaches the same pod. It handles `/api/game-sessions/...` and `/ws`.
- **The `backend` Ingress spreads everything else evenly**: browsing games and the lobby list (`GET /api/game-sessions`).

Each Ingress has its own Service (`30-service.yaml`) because ingress-nginx keeps one load-balancing setting per Service.

## How the lobby lists every pod's sessions

The lobby request reaches one pod, which only holds its own sessions, so that pod asks the others for theirs and merges the answers (`SessionLobby`, `PeerSessions`):

- **The `backend-peers` Service is headless** (`clusterIP: None`): instead of one address that forwards to a pod, its DNS name resolves to the IP of every ready pod. `SESSION_PEERS` in the ConfigMap names it.
- **Each pod answers on its management port, 8081** (`/actuator/gamesessions`), where Actuator lives along with the health probes (`MANAGEMENT_SERVER_PORT`). No Service used by the Ingresses includes that port, so it can't be reached from outside the cluster.
- **A pod doesn't ask itself:** it reads its own sessions directly, and knows its own IP from `POD_IP`, which the Deployment fills in with the downward API (`status.podIP`).
- **A pod that fails or takes longer than a second is left out** for that request, so a slow or dying pod only hides its own sessions for a moment.

Without `SESSION_PEERS`, as when running from IntelliJ, the lobby lists only that instance's sessions, which are all of them.

## Known gaps

- **A pod that stops takes its sessions with it.** That includes a deploy (`kubectl rollout restart` or a new image) and scaling down.
- **The lobby can briefly miss a new pod's sessions or ask a removed pod.** Java caches DNS answers for 30 seconds, so the list of pods can be that far behind. A removed pod just fails and is left out.
- **Changing the number of pods moves some sessions' routing.** The hash is consistent, so only about 1 in N sessions move, but those sessions' new requests (a refresh, a join, a reconnect) reach a pod without them and get a 404, even though their pod is still running. Open WebSocket connections aren't affected. Change `replicas` only when no games are running.

## Prerequisites

- **A local cluster.** In Docker Desktop: Settings → Kubernetes → Enable (choose kubeadm, which shares images with Docker). `kubectl config use-context docker-desktop`.
- **The database:** `docker compose up -d` in the backend folder.
- **The ingress-nginx controller.** It was retired in March 2026 and no longer gets fixes, so use it only on a local cluster. Real clusters are moving to the Gateway API, whose implementations (such as Envoy Gateway) can also hash on a query parameter.

  ```bash
  kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.15.1/deploy/static/provider/cloud/deploy.yaml
  ```

  On Docker Desktop it listens on `localhost` ports 80 and 443. If something else uses port 80, it can't.

## Deploying

From the backend folder:

```bash
./gradlew bootBuildImage --imageName=jeopardy-backend:dev
```

```bash
kubectl apply -f k8s/00-namespace.yaml
```

The password comes from your `.env` (`DATABASE_PASSWORD`). Create the Secret once:

```bash
kubectl create secret generic backend-secret -n jeopardy --from-literal=DATABASE_PASSWORD=<your password>
```

```bash
kubectl apply -f k8s/
```

```bash
kubectl get pods -n jeopardy -w
```

When every pod is `1/1 READY`, the API and the `/ws` WebSocket are at `http://localhost/` (for example `http://localhost/api/games?size=1`), on port 80 instead of 8080. A client must send the `session` parameter on every request about a session (`docs/api.md` §3.2), or those requests can reach the wrong pod.

After rebuilding the image under the same tag, restart the pods to pick it up:

```bash
kubectl rollout restart -n jeopardy deployment/backend
```

## Checking the routing

Create a game from the frontend and copy its id. Asking for it without the parameter reaches either pod, so you get a mix of `200` and `404`:

```bash
for i in 1 2 3 4 5 6; do curl -s -o /dev/null -w "%{http_code} " "http://localhost/api/game-sessions/<id>"; done
```

With the parameter, every request reaches the pod holding it, so you get only `200`:

```bash
for i in 1 2 3 4 5 6; do curl -s -o /dev/null -w "%{http_code} " "http://localhost/api/game-sessions/<id>?session=<id>"; done
```

The lobby list shows every session, whichever pod answers:

```bash
for i in 1 2 3 4; do curl -s "http://localhost/api/game-sessions" | grep -o '"totalElements":[0-9]*'; done
```

To see which pod handles what, follow both pods' logs:

```bash
kubectl logs -n jeopardy -l app=backend -f --prefix
```

## Removing it

```bash
kubectl delete namespace jeopardy
```

That removes everything above except the ingress-nginx controller. To remove that too, run its install command with `delete` instead of `apply`.
