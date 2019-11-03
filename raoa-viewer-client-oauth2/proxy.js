const HttpsProxyAgent = require('https-proxy-agent');
const proxyConfig = [
  {
    context: '/config',
    target: 'http://localhost:8080/',
    secure: false,
    //headers: {host: 'raoa.dev.berg-turbenthal.ch'},
  },
    {
      context: '/rest',
      target: 'http://localhost:8080/',
      secure: false,
      //headers: {host: 'raoa.dev.berg-turbenthal.ch'},
    }
    ,
    {
      context: '/graphql',
      target: 'http://localhost:8080/',
      secure: false,
      //headers: {host: 'raoa.dev.berg-turbenthal.ch'},
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
