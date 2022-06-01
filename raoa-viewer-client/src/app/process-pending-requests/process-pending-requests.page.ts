import {Component, NgZone, OnInit} from '@angular/core';
import {ServerApiService} from '../service/server-api.service';
import {
    ListPendingRequestsGQL,
    ListPendingRequestsQuery, ListPendingRequestsQueryVariables,
    ManageUsersAcceptRequestGQL, ManageUsersAcceptRequestMutation, ManageUsersAcceptRequestMutationVariables,
    ManageUsersRemoveRequestGQL,
    RegistrationRequest
} from '../generated/graphql';
import {LoadingController, ToastController} from '@ionic/angular';
import {Location} from '@angular/common';
import {Router} from '@angular/router';


@Component({
    selector: 'app-process-pending-requests',
    templateUrl: './process-pending-requests.page.html',
    styleUrls: ['./process-pending-requests.page.scss'],
})
export class ProcessPendingRequestsPage implements OnInit {
    public pendingRequests: Array<RegistrationRequest>;

    constructor(private serverApiService: ServerApiService,
                private listPendingRequestsGQL: ListPendingRequestsGQL,
                private loadingController: LoadingController,
                private manageUsersAcceptRequestGQL: ManageUsersAcceptRequestGQL,
                private manageUsersRemoveRequestGQL: ManageUsersRemoveRequestGQL,
                private ngZone: NgZone,
                private toastController: ToastController,
                private location: Location,
                private router: Router
    ) {
    }

    async ngOnInit() {
        await this.reload();
    }

    private async reload() {
        const pendingRequests = await this.serverApiService.query<ListPendingRequestsQuery, ListPendingRequestsQueryVariables>(
            this.listPendingRequestsGQL, {}
        );
        this.ngZone.run(() => this.pendingRequests = pendingRequests.listPendingRequests);
    }

    async accept(request: RegistrationRequest) {
        const loading = await this.loadingController.create({message: 'Akzeptiere Request von ' + request.data.name});
        await loading.present();
        const createdUser = await this.serverApiService.update<ManageUsersAcceptRequestMutation, ManageUsersAcceptRequestMutationVariables>(
            this.manageUsersAcceptRequestGQL, request.authenticationId
        );
        await loading.dismiss();
        await (await this.toastController.create({message: 'Request von ' + request.data.name + ' akzeptiert', duration: 10000}))
            .present();
        await this.serverApiService.clear();
        await this.router.navigate(['/manage-users', 'user', createdUser.createUser.id]);
    }

    async deny(request: RegistrationRequest) {
        const loading = await this.loadingController.create({message: 'Entferne Request von ' + request.data.name});
        await loading.present();
        await this.serverApiService.update(this.manageUsersRemoveRequestGQL, request.authenticationId);
        await loading.dismiss();
        await (await this.toastController.create({message: 'Request von ' + request.data.name + ' entfernt', duration: 10000}))
            .present();
        await this.serverApiService.clear();
        await this.reload();
    }
}
