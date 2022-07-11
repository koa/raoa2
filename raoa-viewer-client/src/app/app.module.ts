import {APP_INITIALIZER, ErrorHandler, LOCALE_ID, NgModule} from '@angular/core';
import {BrowserModule} from '@angular/platform-browser';
import {Router, RouteReuseStrategy} from '@angular/router';

import {IonicModule, IonicRouteStrategy} from '@ionic/angular';

import {AppComponent} from './app.component';
import {AppRoutingModule} from './app-routing.module';
import {HTTP_INTERCEPTORS, HttpClientModule} from '@angular/common/http';
import {AuthenticateInterceptor} from './authenticate-interceptor.service';
import {registerLocaleData} from '@angular/common';
import localeDe from '@angular/common/locales/de';
import localeFr from '@angular/common/locales/fr';
import localeEn from '@angular/common/locales/en';
import {WelcomeComponent} from './redirect-start/welcome.component';
import {ServiceWorkerModule} from '@angular/service-worker';
import {environment} from '../environments/environment';
import {MainMenuComponentModule} from './main-menu/main-menu.module';
import {OAuthModule} from 'angular-oauth2-oidc';
import {FilesizePipe} from './filesize.pipe';
import {GraphQLModule} from './graphql.module';
import * as Sentry from '@sentry/angular';
import {MultiWindowModule} from 'ngx-multi-window';

registerLocaleData(localeDe, 'de');
registerLocaleData(localeFr, 'fr');
registerLocaleData(localeEn, 'en');


@NgModule({
    declarations: [AppComponent, WelcomeComponent, FilesizePipe],
    imports: [
        BrowserModule,
        IonicModule.forRoot(),
        OAuthModule.forRoot({
            resourceServer: {
                allowedUrls: [window.location.href],
                sendAccessToken: true
            }
        }),
        AppRoutingModule,
        HttpClientModule,
        ServiceWorkerModule.register('ngsw-worker.js', {
            enabled: environment.production,
            // Register the ServiceWorker as soon as the app is stable
            // or after 30 seconds (whichever comes first).
            registrationStrategy: 'registerWhenStable:30000'
        }),
        MainMenuComponentModule,
        GraphQLModule,
        MultiWindowModule
    ],
    providers: [
        {provide: LOCALE_ID, useValue: 'de-CH'},
        {provide: RouteReuseStrategy, useClass: IonicRouteStrategy},
        {provide: HTTP_INTERCEPTORS, useClass: AuthenticateInterceptor, multi: true},
        {
            provide: ErrorHandler,
            useValue: Sentry.createErrorHandler({
                showDialog: false,
            }),
        },
        {
            provide: Sentry.TraceService,
            deps: [Router],
        },
        {
            provide: APP_INITIALIZER,
            useFactory: () => () => {
            },
            deps: [Sentry.TraceService],
            multi: true,
        }
    ],
    exports: [],
    bootstrap: [AppComponent]
})
export class AppModule {
}
