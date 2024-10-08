import {Component, NgZone, OnDestroy, OnInit, ViewChild} from '@angular/core';
import {Router} from '@angular/router';
import {AlertController, IonInput, LoadingController, MenuController, ToastController} from '@ionic/angular';
import {CommonServerApiService} from '../service/common-server-api.service';
import {
    CreateTokenGQL,
    CreateTokenMutation,
    CreateTokenMutationVariables,
    GetUserstateGQL,
    GetUserstateQuery,
    GetUserstateQueryVariables,
    Maybe,
    RemoveTokenGQL,
    RemoveTokenMutation,
    RemoveTokenMutationVariables,
    RequestAccessMutationGQL,
    RequestAccessMutationMutation,
    RequestAccessMutationMutationVariables,
    SingleGroupVisibilityUpdate,
    UpdateCredentitalsGQL,
    UpdateCredentitalsMutationVariables,
    WelcomListFetchFnchDataGQL,
    WelcomListFetchFnchDataQuery,
    WelcomListFetchFnchDataQueryVariables
} from '../generated/graphql';
import {ServerApiService} from '../service/server-api.service';
import {LoginService} from '../service/login.service';
import {HttpClient} from '@angular/common/http';
import {FNCH_COMPETITION_ID, FNCH_COMPETITOR_ID} from '../constants';
import {DataService, SyncProgress} from '../service/data.service';
import {bufferTime, filter, map} from 'rxjs/operators';
import {Subscription} from 'rxjs';
import {environment} from '../../environments/environment';
import {SwUpdate} from '@angular/service-worker';

interface FnchEvent {
    pruefungen: FnchCompetition[];
}

interface FnchCompetition {
    id: number;
    resultate: FnchResultEntry[];
}

interface FnchResultEntry {
    rang: number;
    reiter_id: number;
}


@Component({
    selector: 'app-redirect-start',
    templateUrl: './welcome.component.html',
    styleUrls: ['./welcome.component.scss'],
})
export class WelcomeComponent implements OnInit, OnDestroy {

    @ViewChild('message') private message: IonInput;


    public totalPhotoCount = 0;

    public userState: Maybe<GetUserstateQuery>;
    public userName: string;
    public userMail: string;
    public userPicture: string;
    public offlinePhotoCount = 0;
    public syncProgress: SyncProgress | undefined;
    private albumSubscriber: Subscription;
    public syncOfflineSubscription: Subscription;
    public estimation: StorageEstimate;
    public version = environment.version;
    public secretName: string;
    public secretDuration = 1;

    constructor(private router: Router,
                private menu: MenuController,
                private commonServerApiService: CommonServerApiService,
                private ngZone: NgZone,
                private getUserstateGQL: GetUserstateGQL,
                private serverApiService: ServerApiService,
                private loginService: LoginService,
                private requestAccessMutationGQL: RequestAccessMutationGQL,
                private welcomListFetchFnchDataGQL: WelcomListFetchFnchDataGQL,
                private updateCredentitalsGQL: UpdateCredentitalsGQL,
                private createTokenGQL: CreateTokenGQL,
                private removeTokenGQL: RemoveTokenGQL,
                private loadingController: LoadingController,
                private toastController: ToastController,
                private httpClient: HttpClient,
                private dataService: DataService,
                private menuController: MenuController,
                private updates: SwUpdate,
                private alertController: AlertController
    ) {
    }

    async ngOnInit(): Promise<void> {
        try {
            const updated = await this.updates.activateUpdate();
            if (updated) {
                console.log('Updated client');
                document.location.reload();
            }
        } catch (e) {
            console.warn('Fehler beim Update vom Service Worker', e);
        }
        const redirectRoute = sessionStorage.getItem('redirect_route');
        if (redirectRoute !== null) {
            sessionStorage.removeItem('redirect_route');
            const parsedUrl = this.router.parseUrl(redirectRoute);
            console.log('parsed url', parsedUrl);
            await this.router.navigateByUrl(parsedUrl);
        }
        await this.refreshQuota();
        await this.refreshData();
        this.albumSubscriber = this.dataService.albumModified
            .pipe(bufferTime(1000), filter(albums => albums.length > 0))
            .subscribe(updates => {
                this.adjustPhotoCounters();
            });
    }

    public ngOnDestroy() {
        this.albumSubscriber?.unsubscribe();
        this.syncOfflineSubscription?.unsubscribe();
    }

    private async refreshQuota() {
        if (navigator.storage && navigator.storage.estimate) {
            const estimation: StorageEstimate = await navigator.storage.estimate();
            this.ngZone.run(() => {
                this.estimation = estimation;
            });
        }
    }

    private async refreshData() {
        this.adjustPhotoCounters();
        if (navigator.onLine) {
            const userState: Maybe<GetUserstateQuery> = await this.serverApiService.query<GetUserstateQuery, GetUserstateQueryVariables>(
                this.getUserstateGQL, {}
            );
            const userName = await this.loginService.userName();
            const userMail = await this.loginService.userMail();
            const userPicture = await this.loginService.userPicture();
            this.ngZone.run(() => {
                this.userName = userName;
                this.userMail = userMail;
                this.userPicture = userPicture;
                this.userState = userState;
            });
        }
    }

    private adjustPhotoCounters() {
        this.dataService.listAlbums().then(albums => {
            let totalPhotoCount = 0;
            let offlinePhotoCount = 0;
            albums.forEach(albumData => {
                totalPhotoCount += albumData[0].entryCount;
                if (albumData[0].albumVersion === albumData[1]?.offlineSyncedVersion) {
                    offlinePhotoCount += albumData[0].entryCount;
                }
            });
            this.ngZone.run(() => {
                this.totalPhotoCount = totalPhotoCount;
                this.offlinePhotoCount = offlinePhotoCount;
            });
        });
    }

    async logout() {
        await this.loginService.logout();
        await this.router.navigate(['/']);
    }

    async subscribe() {
        const msg = await this.loadingController.create({message: 'Beantrage Zugang'});
        await msg.present();
        const response = await this.serverApiService.update<RequestAccessMutationMutation, RequestAccessMutationMutationVariables>(
            this.requestAccessMutationGQL, {reason: this.message.value as string}
        );
        const success = response.requestAccess.ok;
        const resultCode = response.requestAccess.result;
        await msg.dismiss();
        await this.serverApiService.clear();
        await this.refreshData();
    }

    async updateFnchGroups() {
        // http://info.fnch.ch/startlisten/50450.json
        const loading = await this.loadingController.create({message: 'Daten von info.swiss-equestrian.ch laden'});
        await loading.present();
        const metadata = await this.serverApiService.query<WelcomListFetchFnchDataQuery, WelcomListFetchFnchDataQueryVariables>(
            this.welcomListFetchFnchDataGQL, {}
        );
        const events = new Map<number, string>();
        const eventNames = new Map<number, string>();
        metadata.listAlbums.forEach(albumData => {
            const found = albumData.labels.filter(e => e.labelName === FNCH_COMPETITION_ID).map(e => e.labelValue);
            if (found.length > 0) {
                const eventId = Number.parseInt(found[0], 10);
                events.set(eventId, albumData.id);
                eventNames.set(eventId, albumData.name);
            }
        });
        console.log('events', events);
        const groups = new Map<number, string>();
        metadata.listGroups.forEach(groupData => {
            const found = groupData.labels.filter(e => e.labelName === FNCH_COMPETITOR_ID).map(e => e.labelValue);
            if (found.length > 0) {
                const competitorId = Number.parseInt(found[0], 10);
                groups.set(competitorId, groupData.id);
            }
        });
        console.log('groups', groups);
        const groupUpdates: SingleGroupVisibilityUpdate[] = [];
        for (const [eventId, albumId] of events.entries()) {
            const competitors = new Set<number>();
            const eventData: FnchEvent = await this.httpClient
                .get<FnchEvent>(`https://info.swiss-equestrian.ch/resultate/veranstaltungen/${eventId}.json`)
                .toPromise()
                .catch(error => {
                    this.toastController.create({
                        message: 'Fehler bei ' + eventNames.get(eventId) + ': ' + error,
                        duration: 5000,
                        color: 'danger'
                    }).then(elem => elem.present());
                    return null;
                });
            if (!eventData) {
                continue;
            }
            for (const competitionId of eventData.pruefungen.map(comp => comp.id)) {
                const competitionData: FnchCompetition = await this.httpClient
                    .get<FnchCompetition>(`https://info.swiss-equestrian.ch/resultate/veranstaltungen/${eventId}.json?pruefung_id=${competitionId}`)
                    .toPromise();
                competitionData.resultate.map(e => e.reiter_id).forEach(id => competitors.add(id));
            }
            for (const competitor of competitors) {
                const groupId = groups.get(competitor);
                if (!groupId) {
                    continue;
                }
                groupUpdates.push({
                    isMember: true,
                    groupId,
                    albumId,
                });
            }
        }
        await loading.dismiss();
        const updating = await this.loadingController.create({message: 'Berechtigungen updaten'});
        await updating.present();
        const update: UpdateCredentitalsMutationVariables = {
            update: {
                userUpdates: [],
                groupUpdates,
                groupMembershipUpdates: []
            }
        };
        await this.serverApiService.update(this.updateCredentitalsGQL, update);
        await updating.dismiss();
        const toast = await this.toastController.create({
            message: `${groupUpdates.length} Teilnahmen gefunden in ${events.size} Veranstaltungen`,
            duration: 5000
        });
        await toast.present();
    }

    public openNavigationMenu(): Promise<void> {
        return this.menuController.open('navigation').then();
    }

    public async cleanupCache(): Promise<void> {
        await this.dataService.clearCachedData();
        await this.refreshQuota();
        this.adjustPhotoCounters();
    }

    public syncOffline() {
        this.serverApiService.clear().then();
        this.syncOfflineSubscription = this.dataService.synchronizeData()
            .pipe(bufferTime(500), filter(b => b.length > 0), map(b => b[b.length - 1]))
            .subscribe(state => {
                this.ngZone.run(() => {
                    this.syncProgress = state;
                });
                this.refreshQuota().then();
            }, error => {

            }, () => {
                this.ngZone.run(() => {
                    this.syncProgress = undefined;
                });
                this.refreshQuota().then();
            });
    }

    cancelOfflineSync() {
        this.syncOfflineSubscription.unsubscribe();
        this.refreshQuota().then();
    }

    async createToken(): Promise<void> {
        const duration = this.secretDuration * 1000 * 3600 * 24;
        const metadata = await this.serverApiService.update<CreateTokenMutation, CreateTokenMutationVariables>(
            this.createTokenGQL, {duration, title: this.secretName}
        );
        const password = metadata?.createTemporaryPassword?.password;
        if (password) {
            await navigator.clipboard.writeText(password);
            this.toastController.create({
                message: 'Passwort in Zwischenablage kopiert',
                duration: 5000,
                color: 'primary'
            }).then(elem => elem.present());
        }
        await this.serverApiService.clear();
        await this.refreshData();
    }

    async removeSecret(title: string): Promise<void> {
        const userid = this.userState.currentUser.id;
        await this.serverApiService.update<RemoveTokenMutation, RemoveTokenMutationVariables>(this.removeTokenGQL, {
            update: {
                visibilityUpdates: [],
                removeTemporaryPasswords: [title]
            },
            userId: userid
        });
        await this.serverApiService.clear();
        await this.refreshData();
    }
}
