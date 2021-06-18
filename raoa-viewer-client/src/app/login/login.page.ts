import {Component, OnInit} from '@angular/core';
import {LoginService} from '../service/login.service';
import {Location} from '@angular/common';

@Component({
    selector: 'app-login',
    templateUrl: './login.page.html',
    styleUrls: ['./login.page.scss'],
})
export class LoginPage implements OnInit {
    message: string;
    private auth2: gapi.auth2.GoogleAuth = {} as gapi.auth2.GoogleAuth;

    constructor(private loginService: LoginService, private location: Location) {
    }

    async ngOnInit() {
        if (this.loginService.isSignedIn()) {
            this.location.back();
        }
        await this.loginService.renderLoginButton();
    }

}
