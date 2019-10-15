import {Component, NgZone, OnInit} from '@angular/core';
import {ServerApiService} from '../../services/server-api.service';
import {
  ManageUsersAcceptRequestGQL,
  ManageUsersOverview,
  ManageUsersOverviewGQL,
  ManageUsersRemoveUserGQL
} from '../../generated/graphql';

@Component({
  selector: 'app-manage-users',
  templateUrl: './manage-users.component.html',
  styleUrls: ['./manage-users.component.css']
})
export class ManageUsersComponent implements OnInit {
  public pendingRequests: ManageUsersOverview.ListPendingRequests[] = [];
  public users: ManageUsersOverview.ListUsers[];
  public pendingUsersDisplayColumns = ['icon', 'name', 'email', 'acceptButton', 'removeButton'];
  public usersDisplayColumns = ['icon', 'name', 'email', 'editButton', 'removeButton'];

  constructor(private serverApiService: ServerApiService,
              private manageUsersOverviewGQL: ManageUsersOverviewGQL,
              private manageUsersAcceptRequestGQL: ManageUsersAcceptRequestGQL,
              private manageUsersRemoveUserGQL: ManageUsersRemoveUserGQL
    ,
              private ngZone: NgZone
  ) {
  }

  ngOnInit() {
    this.loadDataFromServer();
  }

  acceptRequest(element: ManageUsersOverview.ListPendingRequests) {
    console.log(element);
    this.serverApiService
      .update(this.manageUsersAcceptRequestGQL, {
        authority: element.authenticationId.authority,
        id: element.authenticationId.id
      })
      .then(result => {
        this.ngZone.run(() => {
          this.loadDataFromServer();
        });
      });
  }

  editUser(element: ManageUsersOverview.ListUsers) {

  }

  removeUser(element: ManageUsersOverview.ListUsers) {
    const handler = this.loadDataFromServer;
    this.serverApiService
      .update(this.manageUsersRemoveUserGQL, {id: element.id})
      .then(result =>
        setTimeout(handler, 3000));
  }

  removeRequest(element: ManageUsersOverview.ListPendingRequests) {

  }

  private loadDataFromServer() {
    this.serverApiService.query(this.manageUsersOverviewGQL, {}).then(result => {
      if (result.listPendingRequests != null) {
        this.pendingRequests = result.listPendingRequests;
        this.users = result.listUsers;
      }
    });
  }
}
