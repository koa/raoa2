import {inject, Injectable} from '@angular/core';
import {ActivatedRouteSnapshot, CanActivateFn, Router, RouterStateSnapshot, UrlTree} from '@angular/router';
import {LoginService} from './service/login.service';
import {DataService} from './service/data.service';

@Injectable({
    providedIn: 'root'
})
export class AuthGuard {


    constructor(
        private loginService: LoginService,
        private router: Router
    ) {
    }

    async isAuthenticated(
        next: ActivatedRouteSnapshot,
        state: RouterStateSnapshot): Promise<boolean | UrlTree> {
        if (navigator.onLine && !await this.loginService.hasValidToken()) {
            return this.router.createUrlTree(['/login'], {queryParams: {target: state.url}});
        }
        return true;
    }
}

export const isAuthenticated: CanActivateFn = async (route, state) => {
    if (!navigator.onLine) {
        return true;
    }
    const loginService = inject(LoginService);
    const router = inject(Router);
    if (!await loginService.hasValidToken()) {
        return router.createUrlTree(['/login'], {queryParams: {target: state.url}});
    }
    return true;
};

export const hasLogin: CanActivateFn = async (route, state) => {
    if (!navigator.onLine) {
        return true;
    }
    const loginService = inject(LoginService);
    const dataService = inject(DataService);
    const router = inject(Router);
    if (!await loginService.hasValidToken()) {
        return router.createUrlTree(['/login'], {queryParams: {target: state.url}});
    }
    const userPermissions = await dataService.userPermission();
    if (!userPermissions.isRegistered) {
        return router.createUrlTree(['/welcome'], {queryParams: {target: state.url}});
    }
    return true;
};
