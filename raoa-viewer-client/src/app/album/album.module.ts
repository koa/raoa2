import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';

import {IonicModule} from '@ionic/angular';

import {AlbumPageRoutingModule} from './album-routing.module';

import {AlbumPage} from './album.page';
import {AuthImagePipe} from './pipe/auth-image.pipe';
import {AngularResizedEventModule} from 'angular-resize-event';
import {InViewportDirective} from './in-viewport.directive';
import {ShowSingleMediaComponent} from './show-single-media/show-single-media.component';

@NgModule({
    imports: [
        CommonModule,
        FormsModule,
        IonicModule,
        AlbumPageRoutingModule,
        AngularResizedEventModule
    ],
    declarations: [AlbumPage, AuthImagePipe, InViewportDirective, ShowSingleMediaComponent],
    exports: [
        AuthImagePipe
    ]
})
export class AlbumPageModule {
}
