import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';

import {IonicModule} from '@ionic/angular';

import {AlbumPageRoutingModule} from './album-routing.module';

import {AlbumPage} from './single-album/album.page';
import {AuthImagePipe} from './pipe/auth-image.pipe';
import {AngularResizedEventModule} from 'angular-resize-event';
import {InViewportDirective} from './in-viewport.directive';
import {ShowSingleMediaComponent} from './show-single-media/show-single-media.component';
import {AlbumListComponent} from './album-list/album-list.component';
import {ManageAlbumComponent} from './manage-album/manage-album.component';
import {SuperTabsModule} from '@ionic-super-tabs/angular';

@NgModule({
    imports: [
        CommonModule,
        FormsModule,
        IonicModule,
        AlbumPageRoutingModule,
        AngularResizedEventModule,
        SuperTabsModule
    ],
    declarations: [AlbumPage, AuthImagePipe, InViewportDirective, ShowSingleMediaComponent, AlbumListComponent, ManageAlbumComponent],
    exports: [
        AuthImagePipe
    ]
})
export class AlbumPageModule {
}
