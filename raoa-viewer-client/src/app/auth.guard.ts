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

    async canActivate(
        next: ActivatedRouteSnapshot,
        state: RouterStateSnapshot): Promise<boolean | UrlTree> {
        if (navigator.onLine && !await this.loginService.hasValidToken()) {
                return this.router.createUrlTree(['/login'], {queryParams: {target: state.url}});
        }
        return true;
    }

}
