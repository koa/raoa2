import {LOCALE_ID, NgModule} from '@angular/core';
import {BrowserModule} from '@angular/platform-browser';
import {RouteReuseStrategy} from '@angular/router';

import {IonicModule, IonicRouteStrategy} from '@ionic/angular';
import {SplashScreen} from '@ionic-native/splash-screen/ngx';
import {StatusBar} from '@ionic-native/status-bar/ngx';

import {AppComponent} from './app.component';
import {AppRoutingModule} from './app-routing.module';
import {HTTP_INTERCEPTORS, HttpClientModule} from '@angular/common/http';
import {AuthenticateInterceptor} from './authenticate-interceptor.service';
import {registerLocaleData} from '@angular/common';
import localeDe from '@angular/common/locales/de';
import localeFr from '@angular/common/locales/fr';
import localeEn from '@angular/common/locales/en';

registerLocaleData(localeDe, 'de');
registerLocaleData(localeFr, 'fr');
registerLocaleData(localeEn, 'en');


@NgModule({
    declarations: [AppComponent],
    entryComponents: [],
    imports: [
        BrowserModule,
        IonicModule.forRoot(),
        AppRoutingModule,
        HttpClientModule
    ],
    providers: [
        {provide: LOCALE_ID, useValue: 'de-CH'},
        StatusBar,
        SplashScreen,
        {provide: RouteReuseStrategy, useClass: IonicRouteStrategy},
        {provide: HTTP_INTERCEPTORS, useClass: AuthenticateInterceptor, multi: true}
    ],
    bootstrap: [AppComponent]
})
export class AppModule {
}
