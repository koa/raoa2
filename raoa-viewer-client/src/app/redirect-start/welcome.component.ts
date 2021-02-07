import {Component, OnInit} from '@angular/core';
import {Router} from '@angular/router';

@Component({
    selector: 'app-redirect-start',
    templateUrl: './welcome.component.html',
    styleUrls: ['./welcome.component.scss'],
})
export class WelcomeComponent implements OnInit {

    constructor(private router: Router) {
    }

    ngOnInit() {
        const redirectRoute = sessionStorage.getItem('redirect_route');
        if (redirectRoute !== null) {
            sessionStorage.removeItem('redirect_route');
            this.router.navigateByUrl(redirectRoute);
        }
    }

}
