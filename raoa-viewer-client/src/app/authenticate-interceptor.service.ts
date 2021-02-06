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
            if (url.startsWith('/rest') || url.startsWith('/graphql')) {
                const auth2 = this.login.auth();
                return new Observable(observer => {
                    auth2.then(config => {
                        next.handle(request).subscribe(observer);
                    }).catch(error => observer.error(error));
                });
            }
        } else {
            console.log('undefined');
        }

        return next.handle(request);
    }
}
