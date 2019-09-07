import {Component, OnInit} from '@angular/core';
import {ServerApiService} from '../../services/server-api.service';

@Component({
  selector: 'app-album-list',
  templateUrl: './album-list.component.html',
  styleUrls: ['./album-list.component.css']
})
export class AlbumListComponent implements OnInit {

  constructor(private serverApi: ServerApiService) {
  }

  ngOnInit() {
    return this.serverApi.listAllAlbums().then(data => {
      console.log(data);
    }).catch(error =>
      console.log(error));
    /*this.authService.authState.subscribe(user => {
      }
    );*/
  }

}
