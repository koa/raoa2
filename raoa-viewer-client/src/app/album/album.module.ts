import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';

import {IonicModule} from '@ionic/angular';

import {AlbumPageRoutingModule} from './album-routing.module';

import {AlbumPage} from './album.page';
import {AuthImagePipe} from './pipe/auth-image.pipe';

@NgModule({
    imports: [
        CommonModule,
        FormsModule,
        IonicModule,
        AlbumPageRoutingModule
    ],
    declarations: [AlbumPage, AuthImagePipe],
    exports: [
        AuthImagePipe
    ]
})
export class AlbumPageModule {
}
