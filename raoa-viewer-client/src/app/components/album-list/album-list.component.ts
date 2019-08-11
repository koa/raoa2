import {Component, OnInit} from '@angular/core';
import {Apollo} from 'apollo-angular';
import gql from 'graphql-tag';
import {Router} from '@angular/router';

interface ListAlbumEntry {
  id: string;
  name: string;
  entryCount: number;
}

interface AuthenticationState {
  state: string;
}

interface GraphQlResponseData {
  listAlbums: ListAlbumEntry[];
  authenticationState: AuthenticationState;
}

@Component({
  selector: 'app-album-list',
  templateUrl: './album-list.component.html',
  styleUrls: ['./album-list.component.css']
})
export class AlbumListComponent implements OnInit {

  albums: ListAlbumEntry[];
  loading = true;
  error: any;

  constructor(private apollo: Apollo, private router: Router) {
  }

    ngOnInit() {
      this.apollo.watchQuery({
          query: gql`
              {
                  listAlbums {
                      id, name, entryCount
                  }
                  authenticationState {
                      state
                  }
              }
          `
      }).valueChanges.subscribe(result => {
        // @ts-ignore
        const responseData: GraphQlResponseData = result.data;
        if (responseData) {
          if (responseData.authenticationState.state === 'AUTHORIZED') {
            this.albums = responseData.listAlbums;
          } else {
            this.router.navigate(['/requestAccess']);
          }
        } else {
          this.loading = result.loading;
          this.error = result.errors;
        }
      });
    }

}
