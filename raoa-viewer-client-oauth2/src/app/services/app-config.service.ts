import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {AppConfig} from '../interfaces/app-config';
import {CookieService} from 'ngx-cookie-service';

declare var gapi: any;

@Injectable({
  providedIn: 'root'
})
export class AppConfigService {

  private expirationDate: Date;

  private auhServicePromise: Promise<any>;

  private appConfig: AppConfig;
  private appConfigPromise: Promise<AppConfig>;

  private basicProfile: gapi.auth2.BasicProfile;
  private auth2: gapi.auth2.GoogleAuth;

  constructor(private http: HttpClient, private cookieService: CookieService) {
  }


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

  initGapi(): Promise<boolean> {
    if (this.auth2 !== undefined) {
      return Promise.resolve(true);
    }
    return new Promise<boolean>((resolve, reject) => {
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
    let forceLogin = false;
    if (this.expirationDate !== undefined && Date.now() > this.expirationDate.getTime()) {
      console.log('session timed out -> renew');
      this.auhServicePromise = undefined;
      forceLogin = true;
    }
    if (this.auhServicePromise === undefined) {
      this.auhServicePromise =
        this.auhServicePromise = new Promise<any>((resolve, reject) => {
            this.initGapi().then(() => {

              let signedInPromise: Promise<gapi.auth2.GoogleUser>;
              if (this.auth2.isSignedIn.get() && !forceLogin) {
                console.log('already logged in');
                signedInPromise = Promise.resolve(this.auth2.currentUser.get());
              } else {
                signedInPromise = this.auth2.signIn().then(user => {
                  const authResponse = user.getAuthResponse(true);
                  const expiresAt = authResponse.expires_at;
                  this.expirationDate = new Date(expiresAt - 10000);
                  const expiresIn = authResponse.expires_in;
                  setTimeout(() => {
                    console.log('login timed out');
                    return this.login();
                  }, expiresIn * 1000);
                  return user;
                });
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
