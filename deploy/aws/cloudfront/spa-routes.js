// CloudFront Function on the frontend's (default) behavior: the browser router's
// paths, such as /sessions/ABCDE or /games/12, aren't files in the bucket, so
// they get index.html and React picks the page. Files keep their own path.
function handler(event) {
  var request = event.request;
  if (request.uri.indexOf('.') === -1) {
    request.uri = '/index.html';
  }
  return request;
}
