import {Component, NgZone, OnInit} from '@angular/core';
import {ServerApiService} from '../../services/server-api.service';
import {
  ManageUsersAcceptRequestGQL,
  ManageUsersOverview,
  ManageUsersOverviewGQL,
  ManageUsersRemoveRequestGQL,
  ManageUsersRemoveUserGQL,
  ManageUsersUpdateAlbumVisibilityGQL,
  ManageUsersUpdateSuperuserGQL,
  ManageUsersUpdateUser,
  ManageUsersUpdateUserGQL
} from '../../generated/graphql';
import {animate, state, style, transition, trigger} from '@angular/animations';
import {MatSlideToggleChange} from '@angular/material';

@Component({
  selector: 'app-manage-users',
  templateUrl: './manage-users.component.html',
  styleUrls: ['./manage-users.component.css'],
  animations: [
    trigger('detailExpand', [
      state('collapsed', style({height: '0px', minHeight: '0'})),
      state('expanded', style({height: '*'})),
      transition('expanded <=> collapsed', animate('225ms cubic-bezier(0.4, 0.0, 0.2, 1)')),
    ]),
  ],
})
export class ManageUsersComponent implements OnInit {
  public pendingRequests: ManageUsersOverview.ListPendingRequests[] = [];
  public users: ManageUsersOverview.ListUsers[];
  public pendingUsersDisplayColumns = ['icon', 'name', 'email', 'acceptButton', 'removeButton'];
  public albums: ManageUsersOverview.ListAlbums[] | null;
  public pendingUpdate: ManageUsersUpdateUser.Variables | null = null;

  constructor(private serverApiService: ServerApiService,
              private manageUsersOverviewGQL: ManageUsersOverviewGQL,
              private manageUsersAcceptRequestGQL: ManageUsersAcceptRequestGQL,
              private manageUsersRemoveUserGQL: ManageUsersRemoveUserGQL,
              private manageUsersRemoveRequestGQL: ManageUsersRemoveRequestGQL,
              private manageUsersUpdateSuperuserGQL: ManageUsersUpdateSuperuserGQL,
              private manageUsersUpdateAlbumVisibilityGQL: ManageUsersUpdateAlbumVisibilityGQL,
              private manageUsersUpdateUserGQL: ManageUsersUpdateUserGQL,
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

  public enableSuperuser(userid: string, enable: boolean) {
    this.preparePendingUpdates(userid);
    this.pendingUpdate.update.canManageUsers = enable;
  }

  public updateAlbumVisibility(userId: string, albumId: string, event: MatSlideToggleChange) {
    this.preparePendingUpdates(userId);
    const enabled: boolean = event.checked;
    for (const update of this.pendingUpdate.update.visibilityUpdates) {
      if (update.albumId === albumId) {
        update.visibility = enabled;
        return;
      }
    }
    this.pendingUpdate.update.visibilityUpdates.push({albumId, visibility: enabled});
  }

  public canUserAccess(userId: string, albumId: string): boolean {
    const listUsers: ManageUsersOverview.ListUsers = this.users.find(u => u.id === userId);
    if (listUsers === undefined) {
      return false;
    }
    return listUsers.canAccess.find(a => a.id === albumId) !== undefined;

  }

  private async loadDataFromServer(): Promise<void> {
    const result = await this.serverApiService.query(this.manageUsersOverviewGQL, {});
    this.ngZone.run(() => {
      if (result.listPendingRequests != null) {
        this.pendingRequests = result.listPendingRequests;
        this.users = result.listUsers;
        this.albums = result.listAlbums
          .filter(a => a.albumTime != null)
          .sort((a, b) => -a.albumTime.localeCompare(b.albumTime));
      }
    });

  }

  storePendingUpdate() {
    if (this.pendingUpdate !== null) {
      const data: ManageUsersUpdateUser.Variables = this.pendingUpdate;
      this.pendingUpdate = null;
      this.serverApiService.update(this.manageUsersUpdateUserGQL, data)
        .then(this.loadDataFromServer)
        .catch(error => console.log(error));
    }
  }

  private preparePendingUpdates(userId: string) {
    if (this.pendingUpdate !== null && this.pendingUpdate.id !== userId) {
      this.storePendingUpdate();
    }
    if (this.pendingUpdate === null) {
      this.pendingUpdate = {
        id: userId, update: {
          canManageUsers: undefined,
          visibilityUpdates: []
        }
      };
    }
  }
}
