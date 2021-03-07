import {Component, NgZone, OnInit, ViewChild} from '@angular/core';
import {Router} from '@angular/router';
import {IonInput, LoadingController, MenuController} from '@ionic/angular';
import {CommonServerApiService} from '../service/common-server-api.service';
import {AuthenticationId, GetUserstateGQL, Maybe, Query, RequestAccessMutationGQL, User, UserInfo} from '../generated/graphql';
import {ServerApiService} from '../service/server-api.service';
import {LoginService} from '../service/login.service';

@Component({
    selector: 'app-redirect-start',
    templateUrl: './welcome.component.html',
    styleUrls: ['./welcome.component.scss'],
})
export class WelcomeComponent implements OnInit {

    @ViewChild('message') private message: IonInput;


    public totalPhotoCount: number;

    public user: gapi.auth2.GoogleUser;
    public userState: Maybe<{ __typename?: 'Query' } &
        Pick<Query, 'authenticationState'> & {
        currentUser?: Maybe<{ __typename?: 'User' } &
            Pick<User, 'canManageUsers'> & { info?: Maybe<{ __typename?: 'UserInfo' } & Pick<UserInfo, 'name' | 'email' | 'picture'>> }>;
        listPendingRequests: Array<{ __typename?: 'RegistrationRequest' } & {
            authenticationId?: Maybe<{ __typename?: 'AuthenticationId' } &
                Pick<AuthenticationId, 'authority' | 'id'>>
        }>
    }>;

    constructor(private router: Router,
                private menu: MenuController,
                private commonServerApiService: CommonServerApiService,
                private ngZone: NgZone,
                private getUserstateGQL: GetUserstateGQL,
                private serverApiService: ServerApiService,
                private loginService: LoginService,
                private requestAccessMutationGQL: RequestAccessMutationGQL,
                private loadingController: LoadingController
    ) {
    }

    ngOnInit() {
        const redirectRoute = sessionStorage.getItem('redirect_route');
        if (redirectRoute !== null) {
            sessionStorage.removeItem('redirect_route');
            this.router.navigateByUrl(redirectRoute);
        }
        this.refreshData();
    }

    private refreshData() {
        this.commonServerApiService.listCollections().then(list => {
            this.ngZone.run(() => this.totalPhotoCount = list.reduce((sum, e) => e.data.entryCount + sum, 0));
        });
        this.loginService.signedInUser().then(user => {
            this.ngZone.run(() => this.user = user);
        });
        this.serverApiService.query(this.getUserstateGQL, {}).then(userState => {
            this.ngZone.run(() => this.userState = userState);
        });
    }

    openMenu() {
        this.menu.open();
    }

    async logout() {
        await this.loginService.logout();
    }

    async subscribe() {
        const msg = await this.loadingController.create({message: 'Beantrage Zugang'});
        await msg.present();
        const response = await this.serverApiService.update(this.requestAccessMutationGQL, {reason: this.message.value as string});
        const success = response.requestAccess.ok;
        const resultCode = response.requestAccess.result;
        console.log(success);
        console.log(resultCode);
        await msg.dismiss();
        await this.serverApiService.clear();
        this.refreshData();
    }
}
