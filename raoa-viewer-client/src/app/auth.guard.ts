import {Injectable} from '@angular/core';
import {ActivatedRouteSnapshot, CanActivate, Router, RouterStateSnapshot, UrlTree} from '@angular/router';
import {Observable, of} from 'rxjs';
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
        state: RouterStateSnapshot): Observable<boolean | UrlTree> | Promise<boolean | UrlTree> | boolean | UrlTree {
        const signedInUser = this.loginService.signedInUser();
        if (signedInUser === undefined) {
            this.router.navigate(['/login'], {queryParams: {target: state.url}});
            return of(false);
        }
        return true;
    }

}
