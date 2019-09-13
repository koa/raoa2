import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {AppConfig} from '../interfaces/app-config';
import {AuthService, AuthServiceConfig, GoogleLoginProvider} from 'angularx-social-login';
import {CookieService} from 'ngx-cookie-service';

@Injectable({
  providedIn: 'root'
})
export class AppConfigService {
  private lastLoginTime = 0;

  constructor(private http: HttpClient, private cookieService: CookieService) {
  }

  private appConfig: AppConfig;
  private appConfigPromise: Promise<AppConfig>;

  private auhService: AuthService;
  private auhServicePromise: Promise<AuthService>;

  loadAppConfig(): Promise<AppConfig> {
    if (this.appConfig !== undefined) {
      return Promise.resolve(this.appConfig);
    }
    if (this.appConfigPromise === undefined) {
      this.appConfigPromise = this.http.get('/config')
        .toPromise()
        .then((data: AppConfig) => {
          console.log('config loaded');
          this.appConfig = data;
          return data;
        });
    }

    return this.appConfigPromise;
  }

  getConfig(): AppConfig {
    return this.appConfig;
  }

  getAuthService(): Promise<AuthService> {
    if (Date.now() - this.lastLoginTime > 30 * 60 * 1000) {
      this.lastLoginTime = Date.now();
      this.auhService = undefined;
      this.auhServicePromise = undefined;
    }
    if (this.auhService !== undefined) {
      return Promise.resolve(this.auhService);
    }
    if (this.auhServicePromise === undefined) {
      this.auhServicePromise =
        this.loadAppConfig().then(config => {
          const authServiceConfig = new AuthServiceConfig([
            {
              id: GoogleLoginProvider.PROVIDER_ID,
              provider: new GoogleLoginProvider(config.googleClientId)
            }
          ]);
          const authService = new AuthService(authServiceConfig);
          this.auhService = authService;
          authService.authState.subscribe(user => {
            if (user != null) {
              this.cookieService.set('access_token', user.idToken);
            } else {
              this.cookieService.delete('access_token');
            }
          });
          return authService;
        });
    }
    return this.auhServicePromise;
  }
}
