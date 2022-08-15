const HttpsProxyAgent = require('https-proxy-agent');
const proxyConfig = [
        {
            context: '/graphql',
            // target: 'https://photos.lkw.teamkoenig.ch/',
            // headers: {host: 'photos.lkw.teamkoenig.ch'},
            // target: 'https://photos.teamkoenig.ch/',
            // headers: {host: 'photos.teamkoenig.ch'},
            target: 'https://photos.berg-turbenthal.ch/',
            headers: {host: 'photos.berg-turbenthal.ch'},
            //target: 'http://localhost:8080/',
            secure: false,

        },
        {
            context: '/graphqlws',
            target: 'https://photos.lkw.teamkoenig.ch/',
            headers: {host: 'photos.lkw.teamkoenig.ch'},
            // target: 'wss://photos.berg-turbenthal.ch/',
            // headers: {host: 'photos.berg-turbenthal.ch'},
            //target: 'http://localhost:8080/',
            secure: false,
            ws: true
        },
        {
            context: '/rest',
            // target: 'https://photos.lkw.teamkoenig.ch/',
            // headers: {host: 'photos.lkw.teamkoenig.ch'},
            // target: 'https://photos.teamkoenig.ch/',
            // headers: {host: 'photos.teamkoenig.ch'},
            target: 'https://photos.berg-turbenthal.ch/',
            headers: {host: 'photos.berg-turbenthal.ch'},
            //target: 'http://localhost:8080/',
            secure: false,
        }
        ,
        {
            context: '/config',
            // target: 'https://photos.lkw.teamkoenig.ch/',
            // headers: {host: 'photos.lkw.teamkoenig.ch'},
            // target: 'https://photos.teamkoenig.ch/',
            // headers: {host: 'photos.teamkoenig.ch'},
            target: 'https://photos.berg-turbenthal.ch/',
            headers: {host: 'photos.berg-turbenthal.ch'},
            // target: 'http://localhost:8080/',
            secure: false,
        }
        ,
        {
            context: '/git',
            target: 'https://photos.lkw.teamkoenig.ch/',
            headers: {host: 'photos.lkw.teamkoenig.ch'},
            //target: 'http://localhost:8080/',
            secure: false,
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
