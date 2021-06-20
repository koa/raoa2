import {Component, OnInit} from '@angular/core';
import {LoginService} from '../service/login.service';
import {ActivatedRoute} from '@angular/router';

@Component({
    selector: 'app-login',
    templateUrl: './login.page.html',
    styleUrls: ['./login.page.scss'],
})
export class LoginPage implements OnInit {
    message: string;
    private auth2: gapi.auth2.GoogleAuth = {} as gapi.auth2.GoogleAuth;

    constructor(private loginService: LoginService,
                private route: ActivatedRoute) {
    }

    async ngOnInit() {
        await this.loginService.renderLoginButton(this.route.snapshot.queryParams.target);
    }

}
