import {Injectable} from '@angular/core';
import {HttpEvent, HttpHandler, HttpInterceptor, HttpRequest} from '@angular/common/http';
import {Observable} from 'rxjs';
import {LoginService} from './service/login.service';

@Injectable()
export class AuthenticateInterceptor implements HttpInterceptor {

    constructor(private login: LoginService) {
    }


    intercept(request: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
        if (this.login !== undefined) {
            const url = request.url;
            if (url.startsWith('/rest') || url.startsWith('/graphql') || url.startsWith('rest') || url.startsWith('graphql')) {
                const auth2 = this.login.idToken();
                // Return the headers as usual
                return new Observable(observer => {
                    auth2.then(token => {
                        next.handle(request.clone({
                            headers: request.headers.set('Authorization', `Bearer ${token}`)
                        })).subscribe(observer);
                    }).catch(error => observer.error(error));
                });
            }
        }
        return next.handle(request);
    }
}
