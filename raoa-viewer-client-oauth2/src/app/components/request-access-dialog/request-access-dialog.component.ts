import {Component, Inject, OnInit} from '@angular/core';
import {MAT_DIALOG_DATA, MatDialogRef} from '@angular/material/dialog';
import {ServerApiService} from '../../services/server-api.service';
import {RequestAccessMutationGQL} from '../../generated/graphql';
import GoogleUser = gapi.auth2.GoogleUser;

@Component({
  selector: 'app-request-access-dialog',
  templateUrl: './request-access-dialog.component.html',
  styleUrls: ['./request-access-dialog.component.css']
})
export class RequestAccessDialogComponent implements OnInit {
  public comment: string;

  constructor(private dialogRef: MatDialogRef<RequestAccessDialogComponent>,
              @Inject(MAT_DIALOG_DATA) public user: GoogleUser,
              private serverApiService: ServerApiService,
              private requestAccessMutationGQL: RequestAccessMutationGQL
  ) {
    console.log('create dialog');
    console.log(user);
  }

  ngOnInit() {
    console.log('init dialog');
  }

  sendRequestAccess() {
    this.serverApiService
      .update(this.requestAccessMutationGQL, {reason: this.comment})
      .then(result => this.dialogRef.close());
  }

  cancel() {
    this.dialogRef.close();
  }
}
