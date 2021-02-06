import {Injectable} from '@angular/core';
import {AppConfig} from '../interfaces/app-config';
import {HttpClient} from '@angular/common/http';

@Injectable({
    providedIn: 'root'
})
export class AppConfigService {
    private appConfig: AppConfig;
    private appConfigPromise: Promise<AppConfig>;

    constructor(private http: HttpClient) {
    }

    async loadAppConfig(): Promise<AppConfig> {
        if (this.appConfig !== undefined) {
            return this.appConfig;
        }
        if (this.appConfigPromise === undefined) {
            const config = this.http.get('/config');
            this.appConfigPromise = config
                .toPromise()
                .then((data: AppConfig) => {
                    console.log('config loaded');
                    this.appConfig = data;
                    return data;
                });
        }
        return this.appConfigPromise;
    }
}
