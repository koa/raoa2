import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Veranstaltung} from '../interfaces/veranstaltung';
import {firstValueFrom} from 'rxjs';
import {Startliste} from '../interfaces/startliste';
import {ServerApiService} from '../../service/server-api.service';
import {
    ListKnownCompetitorsGQL,
    ListKnownCompetitorsQuery,
    ListKnownCompetitorsQueryVariables
} from '../../generated/graphql';

@Injectable({
    providedIn: 'root'
})
export class SvpsDataService {

    private veranstaltungen: Map<number, Veranstaltung> = new Map<number, Veranstaltung>();
    private startlisten: Map<[number, number], Startliste> = new Map<[number, number], Startliste>();

    constructor(private httpClient: HttpClient, private serverApi: ServerApiService, private listKnownCompetitors: ListKnownCompetitorsGQL) {
    }

    public async fetchVeranstaltung(id: number): Promise<Veranstaltung> {
        if (this.veranstaltungen.has(id)) {
            return this.veranstaltungen.get(id);
        }
        const veranstaltung = await firstValueFrom(this.httpClient.get<Veranstaltung>(`https://info.fnch.ch/startlisten/${id}.json`));
        this.veranstaltungen.set(id, veranstaltung);
        return veranstaltung;
    }

    public async fetchStartlist(competitionId: number, listId: number): Promise<Startliste> {
        const key: [number, number] = [competitionId, listId];
        if (this.startlisten.has(key)) {
            return this.startlisten.get(key);
        }
        const startliste = await firstValueFrom(this.httpClient.get<Startliste>(`https://info.fnch.ch/startlisten/${competitionId}.json?startliste_id=${listId}`));
        this.startlisten.set(key, startliste);
        return startliste;
    }

    public async listKownCompetitors(): Promise<Set<number>> {
        const groups = await this.serverApi.query<ListKnownCompetitorsQuery, ListKnownCompetitorsQueryVariables>(this.listKnownCompetitors, {});
        let ret = new Set<number>();
        groups.listGroups.forEach(group => {
            group.labels.forEach(label => {
                if (label.labelName === 'fnch-competitor-id') {
                    ret.add(Number.parseInt(label.labelValue));
                }
            });
        });
        return ret;
    }
}
