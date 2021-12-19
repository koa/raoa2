import {Component, OnDestroy, OnInit} from '@angular/core';
import {LoginService} from '../service/login.service';
import {Subscription} from 'rxjs';
import {ActivatedRoute, Router} from '@angular/router';
import {MenuController} from '@ionic/angular';


@Component({
    selector: 'app-login',
    templateUrl: './login.page.html',
    styleUrls: ['./login.page.scss'],
})
export class LoginPage implements OnInit, OnDestroy {
    public message: string;
    private redirectTarget: string | undefined;
    private routerSubscription: Subscription;

    constructor(private loginService: LoginService,
                private activatedRoute: ActivatedRoute,
                private router: Router,
                private menuController: MenuController
    ) {
    }

    async ngOnInit(): Promise<void> {
        this.routerSubscription = this.activatedRoute.queryParamMap.subscribe(params => {
            this.redirectTarget = params.get('target');
        });
        const redirectTarget = await this.loginService.initoAuth();
        // console.log(redirectTarget);
        if (redirectTarget) {
            await this.router.navigate([redirectTarget]);
        } else if (!navigator.onLine || await this.loginService.hasValidToken()) {
            await this.router.navigate([this.redirectTarget || '/album']);
        }
    }

    ngOnDestroy(): void {
        this.routerSubscription?.unsubscribe();
    }

    async login() {
        await this.loginService.login(this.redirectTarget);
    }

    public openNavigationMenu(): Promise<void> {
        return this.menuController.open('navigation').then();
    }
}
