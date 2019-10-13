import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {AppConfig} from '../interfaces/app-config';
import {CookieService} from 'ngx-cookie-service';
import {Observable, Subscriber} from 'rxjs';

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
  public signedInObservable: Observable<boolean>;
  public currentUserObservable: Observable<gapi.auth2.GoogleUser>;
  private signedInSubscriber: Subscriber<boolean>;
  private currentUserSubscriber: Subscriber<gapi.auth2.GoogleUser>;

  private isSignedIn: boolean;
  private currentUser: gapi.auth2.GoogleUser;

  constructor(private http: HttpClient, private cookieService: CookieService) {
    this.signedInObservable = new Observable<boolean>(subscriber => {
      this.signedInSubscriber = subscriber;
      if (this.isSignedIn !== undefined) {
        subscriber.next(this.isSignedIn);
      }
    });
    this.currentUserObservable = new Observable<gapi.auth2.GoogleUser>(subscriber => {
      this.currentUserSubscriber = subscriber;
      if (this.currentUser !== undefined) {
        subscriber.next(this.currentUser);
      }
    });
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
    const signedInListener = signedIn => {
      this.isSignedIn = signedIn;
      if (this.signedInSubscriber !== undefined) {
        this.signedInSubscriber.next(signedIn);
      }
    };
    const currentUserListener = user => {
      this.currentUser = user;
      if (this.currentUserSubscriber !== undefined) {
        this.currentUserSubscriber.next(user);
      }
    };
    return new Promise<boolean>((resolve, reject) => {
      try {
        gapi.load('auth2', () => {
          this.loadAppConfig().then(config => {
            this.auth2 = gapi.auth2.init({
              client_id: config.googleClientId,
              scope: 'profile'
            });
            this.auth2.currentUser.listen(currentUserListener);
            this.auth2.isSignedIn.listen(signedInListener);
            resolve(true);
          });
        });
      } catch (e) {
        resolve(false);
      }
    });
  }

  renderButton(id) {
    const proc = user => this.loginSuccessful(user);
    this.initGapi().then(() => {
      gapi.signin2.render(id, {
        onsuccess(user: gapi.auth2.GoogleUser): void {
          proc(user);
        }
      });
    });
  }

  takeCurrentUser(): Promise<gapi.auth2.GoogleUser> {
    let forceLogin = false;
    if (this.expirationDate !== undefined && Date.now() > this.expirationDate.getTime()) {
      console.log('session timed out -> renew');
      this.auhServicePromise = undefined;
      forceLogin = true;
    }
    if (this.auhServicePromise === undefined) {
      this.auhServicePromise =
        this.auhServicePromise = new Promise<gapi.auth2.GoogleUser>((resolve, reject) => {
            this.initGapi().then((ok) => {
              if (ok && this.auth2.isSignedIn.get()) {
                resolve(this.auth2.currentUser.get());
              } else {
                resolve(undefined);
              }
            }, error => {
              reject(error);
            });
          }
        );
    }

    return this.auhServicePromise;
  }

  selectUserPrompt(): Promise<gapi.auth2.GoogleUser> {
    const proc = this.loginSuccessful;
    return this.initGapi().then(() => {
      return this.doLogin('select_account');
    }).then(user => {
      proc(user);
      return user;
    });
  }

  logout() {
    if (this.auhServicePromise !== undefined) {
      this.auth2.signOut()
        .then(r => {
          console.log('signed out');
          console.log(r);
        });

      this.auhServicePromise = undefined;
    }
  }

  private loginSuccessful(user: gapi.auth2.GoogleUser) {
    console.log(user.getId());
    console.log(user.getBasicProfile().getEmail());
    const authResponse = user.getAuthResponse(true);
    this.cookieService.set('access_token', authResponse.id_token, authResponse.expires_at, '/');
    this.basicProfile = user.getBasicProfile();

    const expiresAt = authResponse.expires_at;
    this.expirationDate = new Date(expiresAt - 10000);
    const expiresIn = authResponse.expires_in;
    setTimeout(() => {
      console.log('login timed out');
      return this.takeCurrentUser();
    }, expiresIn * 1000);
  }

  private ensureLoggedIn(forceLogin: boolean, prompt: string): Promise<gapi.auth2.GoogleUser> {
    if (this.auth2.isSignedIn.get() && !forceLogin) {
      console.log('already logged in');
      return Promise.resolve(this.auth2.currentUser.get());
    } else {
      return this.doLogin(prompt);
    }
  }

  private doLogin(prompt: string): Promise<gapi.auth2.GoogleUser> {
    return this.auth2.signIn({prompt}).then(user => {
      return user;
    });
  }
}
