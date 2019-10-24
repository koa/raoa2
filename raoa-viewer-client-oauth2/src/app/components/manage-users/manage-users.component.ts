import {Component, NgZone, OnInit} from '@angular/core';
import {ServerApiService} from '../../services/server-api.service';
import {
  ManageUsersAcceptRequestGQL,
  ManageUsersOverview,
  ManageUsersOverviewGQL,
  ManageUsersRemoveRequestGQL,
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
              private manageUsersRemoveUserGQL: ManageUsersRemoveUserGQL,
              private manageUsersRemoveRequestGQL: ManageUsersRemoveRequestGQL,
              private ngZone: NgZone
  ) {
  }

  ngOnInit() {
    this.loadDataFromServer();
  }

  async acceptRequest(element: ManageUsersOverview.ListPendingRequests): Promise<void> {
    await this.serverApiService
      .update(this.manageUsersAcceptRequestGQL, {
        authority: element.authenticationId.authority,
        id: element.authenticationId.id
      });
    await this.reloadData();
  }

  editUser(element: ManageUsersOverview.ListUsers) {

  }

  public async removeUser(element: ManageUsersOverview.ListUsers): Promise<void> {
    await this.serverApiService
      .update(this.manageUsersRemoveUserGQL, {id: element.id});
    return await this.reloadData();

  }

  public async removeRequest(element: ManageUsersOverview.ListPendingRequests): Promise<void> {
    await this.serverApiService
      .update(this.manageUsersRemoveRequestGQL, {
        id: element.authenticationId.id,
        authority: element.authenticationId.authority
      });
    return await this.reloadData();
  }

  private async reloadData(): Promise<void> {
    await this.serverApiService.flushCache();
    await this.loadDataFromServer();
  }

  private async loadDataFromServer(): Promise<void> {
    const result = await this.serverApiService.query(this.manageUsersOverviewGQL, {});
    this.ngZone.run(() => {
      if (result.listPendingRequests != null) {
        this.pendingRequests = result.listPendingRequests;
        this.users = result.listUsers;
      }
    });

  }
}
