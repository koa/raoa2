import {Component, OnDestroy, OnInit} from '@angular/core';
import {LoginService} from '../service/login.service';
import {Subscription} from 'rxjs';
import {ActivatedRoute} from '@angular/router';


@Component({
    selector: 'app-login',
    templateUrl: './login.page.html',
    styleUrls: ['./login.page.scss'],
})
export class LoginPage implements OnInit, OnDestroy {
    message: string;
    subscription: Subscription | undefined;
    private redirectTarget: string | undefined;

    constructor(private loginService: LoginService, activatedRoute: ActivatedRoute
    ) {
        activatedRoute.queryParamMap.subscribe(params => {
            this.redirectTarget = params.get('target');
        });
    }

    async ngOnInit() {
        await this.loginService.initoAuth();
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
