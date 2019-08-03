import {Component, OnInit} from '@angular/core';
import {Apollo} from 'apollo-angular';
import gql from 'graphql-tag';

interface ListAlbumEntry {
  id: string;
  name: string;
  entryCount: number;
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

  constructor(private apollo: Apollo) {
  }

    ngOnInit() {
      this.apollo.watchQuery({
          query: gql`
              {
                  listAlbums {
                      id, name, entryCount
                  }
              }
          `
      }).valueChanges.subscribe(result => {
        // @ts-ignore
        this.albums = result.data && result.data.listAlbums;
        this.loading = result.loading;
        this.error = result.errors;
      });
    }

}
