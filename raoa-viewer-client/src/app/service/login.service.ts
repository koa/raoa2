import {Injectable, NgZone} from '@angular/core';
import {AppConfigService} from './app-config.service';
import {Router} from '@angular/router';
import {Location, LocationStrategy} from '@angular/common';
import {AuthConfig, OAuthService} from 'angular-oauth2-oidc';
import {JwksValidationHandler} from 'angular-oauth2-oidc-jwks';
import {Observable, Subscriber} from 'rxjs';
import {share} from 'rxjs/operators';


interface CachedAuth {
    auth: any;
}

interface GoogleTokenContent {
    iss: string;
    azp: string;
    aud: string;
    sub: string;
    hd: string;
    email: string;
    email_verified: boolean;
    at_hash: string;
    nonce: string;
    name: string;
    picture: string;
    given_name: string;
    family_name: string;
    locale: string;
    iat: number;
    exp: number;
    jti: string;
}

interface JwtTokenContent {
    name: string;
    email: string;
    picture: string;
}


@Injectable({
    providedIn: 'root'
})
export class LoginService {

    public readonly loginObservable: Observable<JwtTokenContent>;
    private loginSubscribe: Subscriber<JwtTokenContent>;

    constructor(private configService: AppConfigService,
                private router: Router,
                private location: Location,
                private ngZone: NgZone,
                private locationStrategy: LocationStrategy,
                private oAuthService: OAuthService
    ) {
        this.loginObservable = new Observable<JwtTokenContent>(subscribe => {
            this.loginSubscribe = subscribe;
        }).pipe(share());

        // this.oAuthService.events.subscribe(event => console.log(event));
    }

    public login(redirectTarget?: string) {
        this.oAuthService.initCodeFlow(redirectTarget);
        // this.oAuthService.initLoginFlow(redirectTarget);
    }

    public async initoAuth() {
        // console.log('init oAuth');
        const appConfigPromise = await this.configService.loadAppConfig();
        const clientId = appConfigPromise.googleClientId;
        const authCodeFlowConfig: AuthConfig = {
            // Url of the Identity Provider
            issuer: 'https://accounts.google.com',

            // URL of the SPA to redirect the user to after login
            redirectUri: window.location.origin + '/login',

            // The SPA's id. The SPA is registerd with this id at the auth-server
            // clientId: 'server.code',
            clientId,

            // Just needed if your auth server demands a secret. In general, this
            // is a sign that the auth server is not configured with SPAs in mind
            // and it might not enforce further best practices vital for security
            // such applications.
            // dummyClientSecret: 'secret',

            responseType: 'token id_token code',

            // set the scope for the permissions the client should request
            // The first four are defined by OIDC.
            // Important: Request offline_access to get a refresh token
            // The api scope is a usecase specific one
            scope: 'openid profile email',

            strictDiscoveryDocumentValidation: false,

            showDebugInformation: false,
            sessionChecksEnabled: false,

            customQueryParams: {
                access_type: 'offline', prompt: 'consent'
            }
        };
        this.oAuthService.configure(authCodeFlowConfig);
        this.oAuthService.tokenValidationHandler = new JwksValidationHandler();

        let state;
        await this.oAuthService.loadDiscoveryDocumentAndTryLogin({
            onTokenReceived: context => {
                const jwtContent = this.auth();
                if (jwtContent && this.loginSubscribe) {
                    this.loginSubscribe.next(jwtContent);
                }
                this.oAuthService.setupAutomaticSilentRefresh();
                state = context.state;
                // console.debug('logged in');
                // console.debug(context);
                // console.debug(state);
            }
        });
        return state;
    }


    public auth(): JwtTokenContent | undefined {
        const identityClaims = this.oAuthService.getIdentityClaims() as GoogleTokenContent;
        if (!identityClaims) {
            return undefined;
        }

        return {email: identityClaims.email, name: identityClaims.name, picture: identityClaims.picture};
    }


    public async hasValidToken(): Promise<boolean> {
        if (this.oAuthService.hasValidAccessToken()) {
            return true;
        }
        if (this.oAuthService.getAccessTokenExpiration() > Date.now()) {
            await this.oAuthService.refreshToken();
        }
        return this.oAuthService.hasValidIdToken() && this.oAuthService.getAccessTokenExpiration() > Date.now();
    }

    public currentValidToken(): string | null {
        return this.oAuthService.getIdToken();
    }


    public async logout(): Promise<void> {
        this.oAuthService.logOut();
    }

    public async userName(): Promise<string> {
        if (await this.hasValidToken()) {
            const jwtTokenContent: JwtTokenContent = this.auth();
            return jwtTokenContent?.name;
        } else {
            return null;
        }
    }

    public async userMail(): Promise<string> {
        if (await this.hasValidToken()) {
            return this.auth()?.email;
        } else {
            return null;
        }
    }

    public async userPicture(): Promise<string> {
        if (await this.hasValidToken()) {
            return this.auth()?.picture;
        } else {
            return null;
        }
    }
}
