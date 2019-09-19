import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {AppConfig} from '../interfaces/app-config';
import {CookieService} from 'ngx-cookie-service';

declare var gapi: any;

@Injectable({
  providedIn: 'root'
})
export class AppConfigService {
  private lastLoginTime = 0;
  private auhServicePromise: Promise<any>;

  constructor(private http: HttpClient, private cookieService: CookieService) {
  }

  private appConfig: AppConfig;
  private appConfigPromise: Promise<AppConfig>;

  private basicProfile: gapi.auth2.BasicProfile;
  private auth2: gapi.auth2.GoogleAuth;


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

  /*logout(): Promise<any> {

  }*/

  initGapi(): Promise<any> {
    return new Promise<any>((resolve, reject) => {
      gapi.load('auth2', () => {
        this.loadAppConfig().then(config => {
          this.auth2 = gapi.auth2.init({
            client_id: config.googleClientId,
            fetch_basic_profile: false,
            scope: 'profile'
          });
          resolve(true);
        });
      });
    });
  }

  login(): Promise<any> {
    if (Date.now() - this.lastLoginTime > 30 * 60 * 1000) {
      this.lastLoginTime = Date.now();
      this.auhServicePromise = undefined;
    }
    if (this.auhServicePromise === undefined) {
      this.auhServicePromise =
        this.auhServicePromise = new Promise<any>((resolve, reject) => {
            this.initGapi().then(() => {

              let signedInPromise: Promise<gapi.auth2.GoogleUser>;
              if (this.auth2.isSignedIn.get()) {
                signedInPromise = Promise.resolve(this.auth2.currentUser.get());
              } else {
                signedInPromise = this.auth2.signIn();
              }
              signedInPromise.then((user) => {
                console.log(user.getId());
                console.log(user.getBasicProfile().getEmail());
                const authResponse = user.getAuthResponse(true);
                this.cookieService.set('access_token', authResponse.id_token, authResponse.expires_at, '/');
                this.basicProfile = user.getBasicProfile();
                resolve(user);
              }, error => {
                reject(error);
              });
            }, error => {
              reject(error);
            });
          }
        );
    }

    return this.auhServicePromise;
  }
}
