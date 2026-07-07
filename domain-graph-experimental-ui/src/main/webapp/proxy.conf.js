// Reads a Personal Access Token from the NTRLOC_DEV_TOKEN environment variable so `ng serve`
// can talk to a secured backend without an interactive login step or session-cookie lifecycle.
// One-time setup: log in at http://localhost:9090/login, then
//   curl -b cookies.txt -c cookies.txt -X POST http://localhost:9090/login \
//     --data-urlencode "username=admin" --data-urlencode "password=admin"
//   curl -b cookies.txt -X POST http://localhost:9090/pat \
//     -H "Content-Type: application/json" -d '{"name":"angular-dev"}'
// then export NTRLOC_DEV_TOKEN=<token> before running `ng serve`.
module.exports = {
  "/api": {
    target: "http://localhost:9090",
    secure: false,
    changeOrigin: true,
    pathRewrite: { "^/api": "" },
    headers: {
      Authorization: `Bearer ${process.env["NTRLOC_DEV_TOKEN"] ?? ""}`
    }
  }
};
