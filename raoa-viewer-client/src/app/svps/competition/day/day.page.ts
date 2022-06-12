import {Component, NgZone, OnInit} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {SvpsDataService} from '../../service/svps-data.service';
import {Startliste, StartlisteZeile} from '../../interfaces/startliste';

@Component({
    selector: 'app-day',
    templateUrl: './day.page.html',
    styleUrls: ['./day.page.scss'],
})
export class DayPage implements OnInit {

    public rows: [Startliste, StartlisteZeile, boolean][] = [];
    public date: Date;

    constructor(private route: ActivatedRoute, private ngZone: NgZone, private dataService: SvpsDataService) {
    }

    ngOnInit() {
        this.route.params.subscribe(async params => {
            const competitionId: number = params.competitionId;
            const day: string = params.day;
            const veranstaltung = await this.dataService.fetchVeranstaltung(competitionId);
            const rows: [Startliste, StartlisteZeile, boolean][] = [];
            const knownCompetitors = await this.dataService.listKnownCompetitors();
            for (const startliste of veranstaltung.startlisten) {
                if (startliste.datum !== day || !startliste.hat_startzeiten) {
                    continue;
                }
                const liste = await this.dataService.fetchStartlist(competitionId, startliste.id);
                for (const startlisteZeile of liste.zeilen) {
                    if (startlisteZeile.typ !== 'starter' || startlisteZeile.kein_start) {
                        continue;
                    }
                    rows.push([startliste, startlisteZeile, knownCompetitors.has(startlisteZeile.reiter_id)]);
                }
            }
            rows.sort((r1, r2) => {
                const t1 = r1[1].startzeit;
                const t2 = r2[1].startzeit;
                return t1.localeCompare(t2);
            });
            this.ngZone.run(() => {
                const regexp = new RegExp('(\\d{4})-(\\d{2})-(\\d{2})');
                const result = day.match(regexp);
                const year = Number.parseInt(result[1], 10);
                const month = Number.parseInt(result[2], 10) - 1;
                const dayOfMonth = Number.parseInt(result[3], 10);
                this.date = new Date(year, month, dayOfMonth);
                this.rows = rows;
            });
        });
    }

}
