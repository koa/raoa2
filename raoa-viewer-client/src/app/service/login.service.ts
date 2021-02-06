import {Injectable} from '@angular/core';
import {AppConfigService} from './app-config.service';


@Injectable({
    providedIn: 'root'
})
export class LoginService {

    private cachingAuth: Promise<gapi.auth2.GoogleAuth>;

    constructor(private configService: AppConfigService) {
    }

    public auth(): Promise<gapi.auth2.GoogleAuth> {
        if (this.cachingAuth !== undefined) {
            return this.cachingAuth;
        }
        //  Create a new Promise where the resolve
        // function is the callback passed to gapi.load
        const pload = new Promise((resolve) => {
            gapi.load('auth2', resolve);
        });
        // When the first promise resolves, it means we have gapi
        // loaded and that we can call gapi.init
        const config = this.configService.loadAppConfig();
        this.cachingAuth = Promise.all([pload, config]).then(async (values) => {
            return gapi.auth2
                .init({client_id: values[1].googleClientId})
                .then(auth => {
                    this.cachingAuth = Promise.resolve(auth);
                    return auth;
                });
        });
        return this.cachingAuth;
    }

}
