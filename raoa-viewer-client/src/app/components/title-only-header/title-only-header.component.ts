import {Component, OnInit} from '@angular/core';

@Component({
  selector: 'app-title-only-header',
  templateUrl: './title-only-header.component.html',
  styleUrls: ['./title-only-header.component.css']
})
export class TitleOnlyHeaderComponent implements OnInit {
  public title: string;

  constructor() {
  }

  ngOnInit() {
  }

}
