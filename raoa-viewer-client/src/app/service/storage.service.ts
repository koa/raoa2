import {Injectable} from '@angular/core';
import Dexie, {Table, WhereClause} from 'dexie';

export interface AlbumData {
    id: string;
    title: string;
    albumVersion: string;
    albumEntryVersion: string;
    lastUpdated: number;
    fnchAlbumId: string;
    entryCount: number;
    albumTime: number;
}

export interface AlbumEntryData {
    albumId: string;
    albumEntryId: string;
    name: string;
    targetWidth: number;
    targetHeight: number;
    created: number;
    keywords: string[];
    entryType: 'image' | 'video';
}

interface PendingKeywordAddEntry {
    albumId: string;
    albumEntryId: string;
    keyword: string;
}

interface PendingKeywordRemoveEntry {
    albumId: string;
    albumEntryId: string;
    keyword: string;
}

@Injectable({
    providedIn: 'root'
})
export class StorageService extends Dexie {
    private readonly albumDataTable: Table<AlbumData, string>;
    private readonly albumEntryDataTable: Table<AlbumEntryData, [string, string]>;
    private readonly pendingKeywordAddDataTable: Table<PendingKeywordAddEntry, [string, string, string]>;
    private readonly pendingKeywordRemoveDataTable: Table<PendingKeywordRemoveEntry, [string, string, string]>;
    private whereAddPendingKeyword: WhereClause<PendingKeywordAddEntry, [string, string, string]>;
    private whereRemovePendingKeyword: WhereClause<PendingKeywordRemoveEntry, [string, string, string]>;

    constructor() {
        super('RaoaDatabase');
        this.version(1)
            .stores({
                albumData: 'id',
                albumEntryData: '[albumEntryId+albumId], albumId, keywords, entryType, created',
                pendingKeywordAddData: '[albumId+albumEntryId+albumId], [albumId+albumEntryId], albumId',
                pendingKeywordRemoveData: '[albumId+albumEntryId+albumId], [albumId+albumEntryId], albumId'
            });
        this.albumDataTable = this.table('albumData');
        this.albumEntryDataTable = this.table('albumEntryData');
        this.pendingKeywordAddDataTable = this.table('pendingKeywordAddData');
        this.pendingKeywordRemoveDataTable = this.table('pendingKeywordRemoveData');
        this.whereAddPendingKeyword = this.pendingKeywordAddDataTable.where(['albumId', 'albumEntryId']);
        this.whereRemovePendingKeyword = this.pendingKeywordRemoveDataTable.where(['albumId', 'albumEntryId']);
    }

    public async updateAlbumEntries(entries: AlbumEntryData[], album?: AlbumData) {
        await this.transaction('rw',
            this.albumDataTable,
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable,
            async () => {
                if (album) {
                    const keepEntries = new Set<string>();
                    entries.forEach(entry => keepEntries.add(entry.albumEntryId));
                    await this.albumDataTable.put(album);
                    await this.albumEntryDataTable
                        .where('albumId')
                        .equals(album.id)
                        .filter(entry => !keepEntries.has(entry.albumEntryId))
                        .delete();
                }
                await this.albumEntryDataTable.bulkPut(entries);
                for (const entry of entries) {
                    await this.adjustPendingKeywords(entry);
                }
            });
    }

    public async listAlbums(): Promise<AlbumData[]> {
        const ret: AlbumData[] = [];
        await this.transaction('rw', this.albumDataTable, async () => this.albumDataTable.each(e => ret.push(e)));
        return ret;
    }

    public async updateAlbums(albums: AlbumData[]) {
        await this.transaction('rw', this.albumDataTable, async () => {
            const dataBefore = new Map<string, AlbumData>();
            await this.albumDataTable.each(oldAlbumEntry => dataBefore.set(oldAlbumEntry.id, oldAlbumEntry));
            const modifiedAlbums: AlbumData[] = [];
            for (const newAlbumEntry of albums) {
                const albumEntryBefore = dataBefore.get(newAlbumEntry.id);
                if (albumEntryBefore === undefined) {
                    newAlbumEntry.albumEntryVersion = undefined;
                    modifiedAlbums.push(newAlbumEntry);
                } else {
                    if (albumEntryBefore.albumVersion !== newAlbumEntry.albumVersion) {
                        modifiedAlbums.push({
                            albumEntryVersion: albumEntryBefore.albumEntryVersion,
                            albumTime: newAlbumEntry.albumTime,
                            albumVersion: newAlbumEntry.albumVersion,
                            entryCount: newAlbumEntry.entryCount,
                            fnchAlbumId: newAlbumEntry.fnchAlbumId,
                            id: newAlbumEntry.id,
                            lastUpdated: Date.now(),
                            title: newAlbumEntry.title
                        });
                    }
                    dataBefore.delete(newAlbumEntry.id);
                }
            }
            this.albumDataTable.bulkPut(modifiedAlbums);
            const remainingKeys: string[] = [];
            dataBefore.forEach((data, key) => remainingKeys.push(key));
            this.albumDataTable.bulkDelete(remainingKeys);
        });
    }

    private async adjustPendingKeywords(entry: AlbumEntryData) {
        const keywords = new Set<string>(entry.keywords);
        await this.whereAddPendingKeyword.equals([entry.albumId, entry.albumEntryId]).each(addEntry => {
            if (keywords.has(addEntry.keyword)) {
                return this.pendingKeywordAddDataTable.delete([entry.albumId, entry.albumEntryId, addEntry.keyword]);
            }
        });
        await this.whereRemovePendingKeyword.equals([entry.albumId, entry.albumEntryId]).each(removeEntry => {
            if (!keywords.has(removeEntry.keyword)) {
                return this.pendingKeywordRemoveDataTable.delete([entry.albumId, entry.albumEntryId, removeEntry.keyword]);
            }
        });
    }

    public async listAlbum(albumId: string, filter?: (entry: AlbumEntryData) => boolean):
        Promise<[AlbumData | undefined, AlbumEntryData[] | undefined]> {
        return this.transaction('r',
            this.albumDataTable,
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable, async () => {
                const foundAlbumData = await this.albumDataTable.get(albumId);
                if (foundAlbumData === undefined) {
                    return [undefined, undefined];
                }
                if (foundAlbumData.albumEntryVersion === undefined
                    || foundAlbumData.albumEntryVersion !== foundAlbumData.albumVersion) {
                    return [foundAlbumData, undefined];
                }
                const entryFilter = filter ? filter : e => true;
                const addKeywords = new Map<string, Set<string>>();
                const removeKeywords = new Map<string, Set<string>>();
                await Promise.all([
                    this.pendingKeywordAddDataTable.where('albumId').equals(albumId).each(entry => {
                        const albumEntryId = entry.albumEntryId;
                        const keyword = entry.keyword;
                        if (addKeywords.has(albumEntryId)) {
                            addKeywords.get(albumEntryId).add(keyword);
                        } else {
                            addKeywords.set(albumEntryId, new Set<string>([keyword]));
                        }
                    }),
                    this.pendingKeywordRemoveDataTable.where('albumId').equals(albumId).each(entry => {
                        const albumEntryId = entry.albumEntryId;
                        const keyword = entry.keyword;
                        if (removeKeywords.has(albumEntryId)) {
                            removeKeywords.get(albumEntryId).add(keyword);
                        } else {
                            removeKeywords.set(albumEntryId, new Set<string>([keyword]));
                        }
                    })]);
                const finalList: AlbumEntryData[] = [];
                (await this.albumEntryDataTable.where('albumId').equals(albumId).filter(entryFilter).sortBy('created'))
                    .forEach(entry => {
                        const keywords = new Set<string>(entry.keywords);
                        if (addKeywords.has(entry.albumEntryId)) {
                            addKeywords.get(entry.albumEntryId).forEach(kw => keywords.add(kw));
                        }
                        if (removeKeywords.has(entry.albumEntryId)) {
                            removeKeywords.get(entry.albumEntryId).forEach(kw => keywords.delete(kw));
                        }
                        const kwList: string[] = [];
                        keywords.forEach(kw => kwList.push(kw));
                        finalList.push({
                            albumEntryId: entry.albumEntryId,
                            albumId: entry.albumId,
                            created: entry.created,
                            entryType: entry.entryType,
                            keywords: kwList,
                            name: entry.name,
                            targetHeight: entry.targetHeight,
                            targetWidth: entry.targetWidth
                        });
                    });
                return [foundAlbumData, finalList];
            });
    }

    public async addKeyword(albumId: string, albumEntryId: string, keyword: string): Promise<void> {
        await this.transaction('rw',
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable,
            () => {
                Promise.all(
                    [
                        this.pendingKeywordRemoveDataTable.delete([albumId, albumEntryId, keyword]).then(v => undefined),
                        this.albumEntryDataTable.get([albumId, albumEntryId])
                            .then((storedEntry: AlbumEntryData | undefined) => {
                                if (storedEntry !== undefined) {
                                    if (storedEntry.keywords !== undefined
                                        && storedEntry.keywords.find(k => k === keyword)) {
                                        return Promise.resolve();
                                    }
                                    return this.pendingKeywordAddDataTable.put({albumEntryId, albumId, keyword}).then(v => undefined);
                                }
                            }).then(v => undefined)]);
            });
    }

    public async removeKeyword(albumId: string, albumEntryId: string, keyword: string): Promise<void> {
        await this.transaction('rw',
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable,
            () => {
                Promise.all(
                    [
                        this.pendingKeywordAddDataTable.delete([albumId, albumEntryId, keyword]).then(v => undefined),
                        this.albumEntryDataTable.get([albumId, albumEntryId])
                            .then((storedEntry: AlbumEntryData | undefined) => {
                                if (storedEntry !== undefined
                                    && storedEntry.keywords !== undefined
                                    && storedEntry.keywords.find(k => k === keyword)) {
                                    return this.pendingKeywordRemoveDataTable.put({
                                        albumEntryId,
                                        albumId,
                                        keyword
                                    }).then(v => undefined);
                                }
                                return Promise.resolve();
                            }).then(v => undefined)]);
            });
    }
}
