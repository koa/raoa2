import {Component, OnDestroy, OnInit} from '@angular/core';
import {LoginService} from '../service/login.service';
import {Subscription} from 'rxjs';
import {ActivatedRoute, Router} from '@angular/router';


@Component({
    selector: 'app-login',
    templateUrl: './login.page.html',
    styleUrls: ['./login.page.scss'],
})
export class LoginPage implements OnInit, OnDestroy {
    message: string;
    subscription: Subscription | undefined;
    private redirectTarget: string | undefined;

    constructor(private loginService: LoginService,
                activatedRoute: ActivatedRoute,
                private router: Router
    ) {
        activatedRoute.queryParamMap.subscribe(params => {
            this.redirectTarget = params.get('target');
        });
    }

    async ngOnInit() {
        await this.loginService.initoAuth();
        if (this.loginService.hasValidToken() || !navigator.onLine) {
            await this.router.navigate([this.redirectTarget || '/album']);
        }
    }

    ngOnDestroy() {
        if (this.subscription !== undefined) {
            this.subscription.unsubscribe();
            this.subscription = undefined;
        }
    }

    async login() {
        await this.loginService.login(this.redirectTarget);
    }
}
