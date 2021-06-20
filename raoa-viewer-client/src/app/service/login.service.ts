import {Injectable, NgZone} from '@angular/core';
import {AppConfigService} from './app-config.service';
import {Router} from '@angular/router';
import {Location, LocationStrategy} from '@angular/common';

interface CachedAuth {
    auth: gapi.auth2.GoogleAuth;
}

@Injectable({
    providedIn: 'root'
})
export class LoginService {

    private cachingAuth: Promise<CachedAuth>;
    private auth2: gapi.auth2.GoogleAuth = {} as gapi.auth2.GoogleAuth;

    constructor(private configService: AppConfigService,
                private router: Router,
                private location: Location,
                private ngZone: NgZone,
                private locationStrategy: LocationStrategy) {
    }

    public async renderLoginButton(target) {
        const appConfigPromise = await this.configService.loadAppConfig();
        const clientId = appConfigPromise.googleClientId;
        window.gapi.load('auth2', () => {
            this.ngZone.run(() => {
                this.auth2 = window.gapi.auth2.init({
                    client_id: clientId
                });
                // console.log('Signed in: ' + this.auth2.isSignedIn.get());
                if (this.auth2.isSignedIn.get() === true) {
                    // console.log('Signed in');
                    this.auth2.signIn();
                }
                this.auth2.attachClickHandler('signin-button', {}, (googleUser: gapi.auth2.GoogleUser) => {
                    if (target) {
                        this.router.navigate([target], {replaceUrl: true});
                    } else {
                        this.router.navigate([], {replaceUrl: true});
                    }
                }, ex => {
                    console.log(ex);
                });

            });
        });

    }

    public isSignedIn(): boolean {
        return this.auth2.isSignedIn?.get();
    }

    public auth(): Promise<CachedAuth> {
        if (this.auth2.isSignedIn?.get() === true) {
            return Promise.resolve({auth: this.auth2});
        }
        this.location.go('/login');
    }

    public signedInUser(): gapi.auth2.GoogleUser {
        if (this.auth2.isSignedIn?.get()) {
            return this.auth2.currentUser.get();
        }
        return undefined;
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
