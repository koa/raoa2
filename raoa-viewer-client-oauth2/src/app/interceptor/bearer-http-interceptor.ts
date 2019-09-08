import {Injectable} from '@angular/core';
import {HttpEvent, HttpHandler, HttpInterceptor, HttpRequest} from '@angular/common/http';
import {Observable} from 'rxjs';
import {AppConfigService} from '../services/app-config.service';
import {SocialUser} from 'angularx-social-login';
import {fromPromise} from 'rxjs/internal-compatibility';

@Injectable()
export class BearerHttpInterceptor implements HttpInterceptor {

  constructor(private appConfig: AppConfigService) {

  }

  // intercept any http call done by the httpClient
  intercept(request: HttpRequest<any>, next: HttpHandler): Observable<HttpEvent<any>> {
    console.log('Request: ' + request.url);
    if (request.url === '/config') {
      return next.handle(request);
    }

    const socialUserPromise: Promise<SocialUser> = this.appConfig.getAuthService().then(authService =>
      new Promise<SocialUser>((resolve, reject) =>
        authService.authState.subscribe(value => resolve(value), error => reject(error), () => resolve())));
    const tokenPromise: Promise<string> = socialUserPromise.then(user => user.idToken);
    const ret: Promise<HttpEvent<any>> = tokenPromise.then(jwtToken => request.clone({
        headers: request.headers.set('authorization', `Bearer ${jwtToken}`)
      }), () => request
    ).then(requestToHandle => next.handle(requestToHandle).toPromise());

    return fromPromise(ret);

  }
}
