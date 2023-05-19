import {Component, OnInit} from '@angular/core';
import { register } from 'swiper/element/bundle';

register();

@Component({
    selector: 'app-root',
    templateUrl: 'app.component.html',
    styleUrls: ['app.component.css']
})
export class AppComponent implements OnInit {
    constructor() {
        this.initializeApp();
    }


    initializeApp() {
    }

    ngOnInit() {
    }


}
