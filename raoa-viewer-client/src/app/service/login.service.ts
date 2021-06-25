import {Injectable, NgZone} from '@angular/core';
import {AppConfigService} from './app-config.service';
import {Router} from '@angular/router';
import {Location, LocationStrategy} from '@angular/common';
import GoogleUser = gapi.auth2.GoogleUser;

interface CachedAuth {
    auth: gapi.auth2.GoogleAuth;
}

@Injectable({
    providedIn: 'root'
})
export class LoginService {

    constructor(private configService: AppConfigService,
                private router: Router,
                private location: Location,
                private ngZone: NgZone,
                private locationStrategy: LocationStrategy) {
    }

    private cachingAuth: Promise<CachedAuth>;
    private auth2: gapi.auth2.GoogleAuth = {} as gapi.auth2.GoogleAuth;

    private static storeCurrentToken(authResponse: gapi.auth2.AuthResponse, user: gapi.auth2.GoogleUser) {
        localStorage.setItem('token', authResponse.id_token);
        const basicProfile = user.getBasicProfile();
        localStorage.setItem('username', basicProfile.getName());
        localStorage.setItem('usermail', basicProfile.getEmail());
        localStorage.setItem('userpicture', basicProfile.getImageUrl());
        localStorage.setItem('token_expires', authResponse.expires_at.toString());
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
                this.auth2.attachClickHandler('signin-button', {}, async (googleUser: gapi.auth2.GoogleUser) => {
                    const user: GoogleUser = googleUser;
                    const authResponse = user.getAuthResponse(true);
                    if (authResponse === null || authResponse === undefined || Date.now() > authResponse.expires_at) {
                        const reloadedResponse = await user.reloadAuthResponse();
                        LoginService.storeCurrentToken(reloadedResponse, user);
                    } else {
                        LoginService.storeCurrentToken(authResponse, user);
                    }
                    this.ngZone.run(() => {
                        if (target) {
                            this.router.navigate([target], {replaceUrl: true});
                        } else {
                            this.router.navigate([], {replaceUrl: true});
                        }
                    });
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

    private signedInUser(): gapi.auth2.GoogleUser {
        if (this.auth2.isSignedIn?.get()) {
            return this.auth2.currentUser.get();
        }
        return undefined;
    }

    public hasValidToken(): boolean {
        const expiration = localStorage.getItem('token_expires');
        return expiration !== null && Date.now() < Number.parseInt(expiration, 10);
    }

    public currentValidToken(): string | null {
        if (this.hasValidToken()) {
            return localStorage.getItem('token');
        } else {
            return null;
        }
    }


    public async logout(): Promise<void> {
        localStorage.removeItem('token_expires');
        if (this.auth2) {
            console.log(this.auth2.signOut);
            this.auth2.signOut();
            this.auth2.disconnect();
        }
        location.reload();
    }

    public userName(): string {
        if (this.hasValidToken()) {
            return localStorage.getItem('username');
        } else {
            return null;
        }
    }

    public userMail(): string {
        if (this.hasValidToken()) {
            return localStorage.getItem('usermail');
        } else {
            return null;
        }
    }

    public userPicture(): string {
        if (this.hasValidToken()) {
            return localStorage.getItem('userpicture');
        } else {
            return null;
        }
    }
}
