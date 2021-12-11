import {Injectable} from '@angular/core';
import {ActivatedRouteSnapshot, CanActivate, Router, RouterStateSnapshot, UrlTree} from '@angular/router';
import {LoginService} from './service/login.service';

@Injectable({
    providedIn: 'root'
})
export class AuthGuard implements CanActivate {


    constructor(
        private loginService: LoginService,
        private router: Router
    ) {
    }

    canActivate(
        next: ActivatedRouteSnapshot,
        state: RouterStateSnapshot): boolean | UrlTree {
        const validToken = this.loginService.hasValidToken();
        if (!validToken && navigator.onLine) {
            return this.router.createUrlTree(['/login'], {queryParams: {target: state.url}});
        }
        return true;
    }

}
