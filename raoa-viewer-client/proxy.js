const HttpsProxyAgent = require('https-proxy-agent');
const proxyConfig = [{
  context: '/graphql',
  target: 'http://localhost:8080/',
  secure: false
}, {
  context: '/oauth2',
  target: 'http://localhost:8080/',
  secure: false
}, {
  context: '/login',
  target: 'http://localhost:8080/',
  secure: false
}, {
  context: '/logout',
  target: 'http://localhost:8080/',
  secure: false
}, {
  context: '/rest',
  target: 'http://localhost:8080/',
  secure: false
}];

function setupForCorporateProxy(proxyConfig) {
  var proxyServer = process.env.http_proxy || process.env.HTTP_PROXY;
  if (proxyServer) {
    var agent = new HttpsProxyAgent(proxyServer);
    console.log('Using corporate proxy server: ' + proxyServer);
    proxyConfig.forEach(function (entry) {
      entry.agent = agent;
    });
  }
  return proxyConfig;
}

module.exports = setupForCorporateProxy(proxyConfig);
