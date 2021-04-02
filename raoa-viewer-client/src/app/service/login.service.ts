import {Injectable} from '@angular/core';
import {AppConfigService} from './app-config.service';
import {Router} from '@angular/router';


@Injectable({
    providedIn: 'root'
})
export class LoginService {

    private cachingAuth: Promise<gapi.auth2.GoogleAuth>;

    constructor(private configService: AppConfigService, private router: Router) {
    }

    public auth(): Promise<gapi.auth2.GoogleAuth> {
        if (this.cachingAuth !== undefined) {
            return this.cachingAuth;
        }
        //  Create a new Promise where the resolve
        // function is the callback passed to gapi.load
        const pload = new Promise((resolve) => {
            window.onload = () => gapi.load('auth2', resolve);
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
                    fetch_basic_profile: false
                })
                .then(auth => {
                    this.cachingAuth = Promise.resolve(auth);
                    return auth;
                });
        });
        return this.cachingAuth;
    }

    public async signedInUser(): Promise<gapi.auth2.GoogleUser> {
        const [auth] = await Promise.all([this.auth()]);
        if (auth.isSignedIn.get()) {
            return auth.currentUser.get();
        }
        // const lastTry = sessionStorage.getItem('try_login');
        // const firstTry = lastTry == null || Date.now() - parseInt(lastTry, 10) > 1000 * 60;
        // const uxMode = firstTry ? 'redirect' : 'popup';
        sessionStorage.setItem('redirect_route', this.router.url);
        sessionStorage.setItem('try_login', Date.now().toString(10));
        return await auth.signIn({
            ux_mode: 'redirect',
            redirect_uri: window.location.origin,
            // scope: 'profile email'
        }).then((result) => {
            sessionStorage.removeItem('try_login');
            return result;
        });
    }

    public async idToken(): Promise<string> {
        const user = await this.signedInUser();
        const authResponse = user.getAuthResponse(true);
        if (authResponse === null || authResponse === undefined || Date.now() > authResponse.expires_at) {
            console.log('refresh token');
            const reloadedResponse = await user.reloadAuthResponse();
            console.log('reloaded');
            console.log(reloadedResponse);
            return reloadedResponse.id_token;
        }
        return authResponse.id_token;
    }

    public async logout(): Promise<void> {
        const [auth] = await Promise.all([this.auth()]);
        auth.signOut();
        auth.disconnect();
        sessionStorage.removeItem('try_login');
        location.reload();
    }

}
