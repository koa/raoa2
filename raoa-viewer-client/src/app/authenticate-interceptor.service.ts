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
                if (this.login.hasValidToken()) {
                    const validToken = this.login.currentValidToken();
                    return new Observable(observer => {
                        next.handle(request.clone({
                            headers: request.headers.set('Authorization', `Bearer ${validToken}`)
                        })).subscribe(observer);
                    });

                }
            }
        }
        return next.handle(request);
    }
}
