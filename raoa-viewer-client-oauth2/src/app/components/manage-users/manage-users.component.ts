import {Component, NgZone, OnInit} from '@angular/core';
import {ServerApiService} from '../../services/server-api.service';
import {ManageUsersOverview, ManageUsersOverviewGQL} from '../../generated/graphql';

@Component({
  selector: 'app-manage-users',
  templateUrl: './manage-users.component.html',
  styleUrls: ['./manage-users.component.css']
})
export class ManageUsersComponent implements OnInit {
  public pendingRequests: ManageUsersOverview.ListPendingRequests[] = [];
  public displayedColumns = ['icon', 'name', 'email'];

  constructor(private serverApiService: ServerApiService,
              private manageUsersOverviewGQL: ManageUsersOverviewGQL,
              private ngZone: NgZone) {
  }

  ngOnInit() {
    this.serverApiService.query(this.manageUsersOverviewGQL, {}).then(result => {
      if (result.listPendingRequests != null) {
        this.pendingRequests = result.listPendingRequests;
      }
    });
  }

}
