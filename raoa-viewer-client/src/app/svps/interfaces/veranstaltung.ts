import {Startliste} from './startliste';

export interface Veranstaltung {
    veranstaltung: VeranstaltungMetadata;
    startlisten: [Startliste];
}

export interface VeranstaltungMetadata {
    datum: string;
    ort: string;
}
