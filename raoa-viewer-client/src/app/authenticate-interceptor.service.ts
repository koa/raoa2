import {Injectable} from '@angular/core';
import {HttpEvent, HttpHandler, HttpInterceptor, HttpRequest} from '@angular/common/http';
import {from, Observable} from 'rxjs';
import {LoginService} from './service/login.service';
import {mergeMap} from 'rxjs/operators';

@Injectable()
export class AuthenticateInterceptor implements HttpInterceptor {

    constructor(private login: LoginService) {
    }


    async doIntercept(request: HttpRequest<unknown>, next: HttpHandler): Promise<Observable<HttpEvent<unknown>>> {
        if (this.login !== undefined) {
            const url = request.url;
            if (url.startsWith('/rest') || url.startsWith('/graphql') || url.startsWith('rest') || url.startsWith('graphql')) {
                if (await this.login.hasValidToken()) {
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

    intercept(req: HttpRequest<any>, next: HttpHandler): Observable<HttpEvent<any>> {
        return from(this.doIntercept(req, next)).pipe(mergeMap(x => x));
    }
}
