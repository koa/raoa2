import {Injectable} from '@angular/core';
import {AppConfigService} from './app-config.service';
import {Router} from '@angular/router';
import {Location} from '@angular/common';

interface CachedAuth {
    auth: gapi.auth2.GoogleAuth;
}

@Injectable({
    providedIn: 'root'
})
export class LoginService {

    private cachingAuth: Promise<CachedAuth>;

    constructor(private configService: AppConfigService, private router: Router, private location: Location) {
    }

    public auth(): Promise<CachedAuth> {
        if (this.cachingAuth !== undefined) {
            return this.cachingAuth;
        }
        //  Create a new Promise where the resolve
        // function is the callback passed to gapi.load
        const pload = new Promise((resolve) => {
            window.onload = () => gapi.load('auth2', done => {
                console.log(done);
                resolve(done);
            });
            // gapi.load('auth2', resolve);
        });

        // When the first promise resolves, it means we have gapi
        // loaded and that we can call gapi.init
        const config = this.configService.loadAppConfig();
        this.cachingAuth = Promise.all([pload, config]).then(async (values) => {
            return gapi.auth2
                .init({
                    client_id: values[1].googleClientId,
                    scope: 'profile email openid',
                    fetch_basic_profile: true
                })
                .then((auth) => {
                    const result = {auth};
                    this.cachingAuth = Promise.resolve(result);
                    return result;
                });
        });
        return this.cachingAuth;
    }

    public async signedInUser(): Promise<gapi.auth2.GoogleUser> {
        const [auth] = await Promise.all([this.auth()]);


        if (auth.auth.isSignedIn.get()) {
            return auth.auth.currentUser.get();
        }
        // const lastTry = sessionStorage.getItem('try_login');
        // const firstTry = lastTry == null || Date.now() - parseInt(lastTry, 10) > 1000 * 60;

        // const ua = navigator.userAgent;
        // const uxMode = /Android/i.test(ua) ? 'popup' : 'redirect';
        sessionStorage.setItem('redirect_route', this.router.url);
        sessionStorage.setItem('try_login', Date.now().toString(10));
        const result = await auth.auth.signIn({
            ux_mode: 'popup',
            redirect_uri: window.location.origin,
            fetch_basic_profile: true,
            scope: 'profile email'
        });
        sessionStorage.removeItem('try_login');
        location.reload();
        return result;

    }

    public async idToken(): Promise<string> {
        const user = await this.signedInUser();
        const authResponse = user.getAuthResponse(true);
        if (authResponse === null || authResponse === undefined || Date.now() > authResponse.expires_at) {
            const reloadedResponse = await user.reloadAuthResponse();
            return reloadedResponse.id_token;
        }
        return authResponse.id_token;
    }

    public async logout(): Promise<void> {
        const [auth] = await Promise.all([this.auth()]);
        auth.auth.signOut();
        auth.auth.disconnect();
        sessionStorage.removeItem('try_login');
        location.reload();
    }

}
