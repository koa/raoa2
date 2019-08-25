import {Component} from '@angular/core';

@Component({
  selector: 'app-album-content-header',
  templateUrl: './album-content-header.component.html',
  styleUrls: ['./album-content-header.component.css']
})
export class AlbumContentHeaderComponent {
  public title: string;
  public zoomIn: () => void;
  public zoomOut: () => void;
  public downloadZip: () => void;
  public startSync: () => void;
  public syncRunning: boolean;
  public progressBarMode: 'determinate' | 'indeterminate' | 'buffer' | 'query' = 'indeterminate';
  public progressBarValue: number;


  constructor() {
  }

}
