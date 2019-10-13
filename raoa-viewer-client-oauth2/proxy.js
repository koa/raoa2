const HttpsProxyAgent = require('https-proxy-agent');
const proxyConfig = [{
    context: '/config',
    target: 'http://raoa.dev.berg-turbenthal.ch/',
    secure: false,
    headers: {host: 'raoa.dev.berg-turbenthal.ch'},
    xfwd: true
  },
    {
      context: '/rest',
      target: 'http://raoa.dev.berg-turbenthal.ch/',
      secure: false,
      headers: {host: 'raoa.dev.berg-turbenthal.ch'},
      xfwd: true
    }
    ,
    {
      context: '/graphql',
      target: 'http://raoa.dev.berg-turbenthal.ch/',
      secure: false,
      headers: {host: 'raoa.dev.berg-turbenthal.ch'},
      xfwd: true
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
