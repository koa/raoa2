const HttpsProxyAgent = require('https-proxy-agent');
const proxyConfig = [
    {
      context: '/config',
      target: 'https://photos.berg-turbenthal.ch/',
      secure: false,
      headers: {host: 'photos.berg-turbenthal.ch'},
    },
    {
      context: '/rest',
      target: 'https://photos.berg-turbenthal.ch/',
      secure: false,
      headers: {host: 'photos.berg-turbenthal.ch'},
    }
    ,
    {
      context: '/graphql',
      target: 'https://photos.berg-turbenthal.ch/',
      secure: false,
      headers: {host: 'photos.berg-turbenthal.ch'},
    }
  ]
;

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
