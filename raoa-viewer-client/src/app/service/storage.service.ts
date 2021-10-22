import {Injectable} from '@angular/core';
import Dexie, {Table, WhereClause} from 'dexie';
import Semaphore from 'semaphore-async-await';

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
    contentType: string;
    entryType: 'image' | 'video';
    cameraModel: string;
    exposureTime: number;
    fNumber: number;
    focalLength35: number;
    isoSpeedRatings: number;
}

export interface ImageBlob {
    albumId: string;
    albumEntryId: string;
    mediaSize: number;
    data: Blob;
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

export interface KeywordState {
    existingKeywords: Set<string>;
    pendingAddKeywords: Set<string>;
    pendingRemoveKeywords: Set<string>;
}


@Injectable({
    providedIn: 'root'
})
export class StorageService extends Dexie {


    constructor() {
        super('RaoaDatabase');
        this.version(2)
            .stores({
                albumData: 'id',
                albumEntryData: '[albumId+albumEntryId], albumId, keywords, entryType, created',
                pendingKeywordAddData: '[albumId+albumEntryId+keyword], [albumId+albumEntryId], albumId',
                pendingKeywordRemoveData: '[albumId+albumEntryId+keyword], [albumId+albumEntryId], albumId',
                images: '[albumId+albumEntryId], albumId'
            });
        this.albumDataTable = this.table('albumData');
        this.albumEntryDataTable = this.table('albumEntryData');
        this.pendingKeywordAddDataTable = this.table('pendingKeywordAddData');
        this.pendingKeywordRemoveDataTable = this.table('pendingKeywordRemoveData');
        this.imagesTable = this.table('images');
        this.whereAddPendingKeyword = this.pendingKeywordAddDataTable.where(['albumId', 'albumEntryId']);
        this.whereRemovePendingKeyword = this.pendingKeywordRemoveDataTable.where(['albumId', 'albumEntryId']);
        this.albumEntryByEntryAndAlbum = this.albumEntryDataTable.where(['albumId', 'albumEntryId']);
    }

    private readonly albumDataTable: Table<AlbumData, string>;
    private readonly albumEntryDataTable: Table<AlbumEntryData, [string, string]>;
    private readonly pendingKeywordAddDataTable: Table<PendingKeywordAddEntry, [string, string, string]>;
    private readonly pendingKeywordRemoveDataTable: Table<PendingKeywordRemoveEntry, [string, string, string]>;
    private readonly imagesTable: Table<ImageBlob, [string, string]>;
    private readonly whereAddPendingKeyword: WhereClause<PendingKeywordAddEntry, [string, string, string]>;
    private readonly whereRemovePendingKeyword: WhereClause<PendingKeywordRemoveEntry, [string, string, string]>;
    private readonly albumEntryByEntryAndAlbum: WhereClause<AlbumEntryData, [string, string]>;
    private readonly lastAccessTime = new Map<string, number>();
    private readonly cleanupSemaphore = new Semaphore(1);

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
        await this.whereAddPendingKeyword
            .equals([entry.albumId, entry.albumEntryId])
            .each(addEntry => {
                if (keywords.has(addEntry.keyword)) {
                    return this.pendingKeywordAddDataTable.delete([entry.albumId, entry.albumEntryId, addEntry.keyword]);
                }
            });
        await this.whereRemovePendingKeyword
            .equals([entry.albumId, entry.albumEntryId])
            .each(removeEntry => {
                if (!keywords.has(removeEntry.keyword)) {
                    return this.pendingKeywordRemoveDataTable.delete([entry.albumId, entry.albumEntryId, removeEntry.keyword]);
                }
            });
    }

    public async listAlbum(albumId: string, filter?: (entry: AlbumEntryData) => boolean):
        Promise<[AlbumData | undefined, AlbumEntryData[] | undefined]> {
        this.lastAccessTime.set(albumId, Date.now());
        return this.transaction('r',
            [this.albumDataTable, this.albumEntryDataTable, this.pendingKeywordAddDataTable, this.pendingKeywordRemoveDataTable],
            async (tx) => {
                try {
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
                                cameraModel: entry.cameraModel,
                                exposureTime: entry.exposureTime,
                                fNumber: entry.fNumber,
                                focalLength35: entry.focalLength35,
                                isoSpeedRatings: entry.isoSpeedRatings,
                                albumEntryId: entry.albumEntryId,
                                albumId: entry.albumId,
                                created: entry.created,
                                entryType: entry.entryType,
                                keywords: kwList,
                                name: entry.name,
                                targetHeight: entry.targetHeight,
                                targetWidth: entry.targetWidth,
                                contentType: entry.contentType
                            });
                        });
                    return [foundAlbumData, finalList];
                } catch (e) {
                    console.log('error');
                    console.log(e);
                    throw e;
                }
            });
    }

    public async addKeyword(albumId: string, albumEntryIds: string[], keywords: string[]): Promise<void> {
        await this.transaction('rw',
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable,
            async () => {
                for (const albumEntryId of albumEntryIds) {
                    for (const keyword of keywords) {
                        await Promise.all(
                            [
                                this.pendingKeywordRemoveDataTable.delete([albumId, albumEntryId, keyword]).then(v => undefined),
                                this.albumEntryDataTable.get([albumId, albumEntryId])
                                    .then((storedEntry: AlbumEntryData | undefined) => {
                                        if (storedEntry !== undefined) {
                                            if (storedEntry.keywords !== undefined
                                                && storedEntry.keywords.find(k => k === keyword)) {
                                                return Promise.resolve();
                                            }
                                            return this.pendingKeywordAddDataTable.put({
                                                albumId,
                                                albumEntryId,
                                                keyword
                                            }).then(v => undefined);
                                        }
                                    }).then(v => undefined)]);
                    }
                }
            });
    }

    public async removeKeyword(albumId: string, albumEntryIds: string[], keywords: string[]): Promise<void> {
        await this.transaction('rw',
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable,
            async () => {
                for (const keyword of keywords) {
                    for (const albumEntryId of albumEntryIds) {
                        await Promise.all(
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
                    }
                }
            });
    }

    public async currentKeywordStates(albumId: string, entries: Set<string>): Promise<Map<string, KeywordState>> {
        return this.transaction('r',
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable,
            async () => {
                const entryKeys: [string, string][] = [];
                entries.forEach(albumEntryId => entryKeys.push([albumId, albumEntryId]));
                const ret = new Map<string, KeywordState>();
                const entryFunction = entryId => {
                    const existingEntry = ret.get(entryId);
                    if (existingEntry !== undefined) {
                        return existingEntry;
                    }
                    const newEntry = {
                        existingKeywords: new Set<string>(),
                        pendingAddKeywords: new Set<string>(),
                        pendingRemoveKeywords: new Set<string>()
                    };
                    ret.set(entryId, newEntry);
                    return newEntry;
                };
                await Promise.all([
                    this.albumEntryByEntryAndAlbum
                        .anyOf(entryKeys)
                        .each(entry => {
                            const keywords = entryFunction(entry.albumEntryId).existingKeywords;
                            entry.keywords.forEach(kw => keywords.add(kw));
                        }),
                    this.pendingKeywordAddDataTable
                        .where(['albumId', 'albumEntryId'])
                        .anyOf(entryKeys)
                        .each(addEntry => entryFunction(addEntry.albumEntryId).pendingAddKeywords.add(addEntry.keyword)),
                    this.pendingKeywordRemoveDataTable
                        .where(['albumId', 'albumEntryId'])
                        .anyOf(entryKeys)
                        .each(removeEntry => entryFunction(removeEntry.albumEntryId).pendingRemoveKeywords.add(removeEntry.keyword))
                ]);
                return ret;
            });
    }

    public async pendingAddKeywords(albumId: string, addEntryCallback: (PendingKeywordAddEntry) => void) {
        await this.transaction('r', this.pendingKeywordAddDataTable, async () => {
            this.pendingKeywordAddDataTable.where('albumId').equals(albumId).each(addEntryCallback);
        });
    }

    public async pendingRemoveKeywords(albumId: string, removeEntryCallback: (PendingKeywordRemoveEntry) => void) {
        await this.transaction('r', this.pendingKeywordRemoveDataTable, async () => {
            this.pendingKeywordRemoveDataTable.where('albumId').equals(albumId).each(removeEntryCallback);
        });
    }

    public hasPendingMutations(albumId: string): Promise<boolean> {
        return this.transaction('r', this.pendingKeywordAddDataTable, this.pendingKeywordRemoveDataTable, async () => {
            const [addCount, removeCount] = await Promise.all([
                this.pendingKeywordAddDataTable.where('albumId').equals(albumId).count(),
                this.pendingKeywordRemoveDataTable.where('albumId').equals(albumId).count()
            ]);
            return addCount + removeCount > 0;
        });
    }

    public clearPendingMutations(albumId: string): Promise<void> {
        return this.transaction('rw', this.pendingKeywordAddDataTable, this.pendingKeywordRemoveDataTable, async () => {
            await Promise.all([
                this.pendingKeywordAddDataTable.where('albumId').equals(albumId).delete(),
                this.pendingKeywordRemoveDataTable.where('albumId').equals(albumId).delete()
            ]);
        });
    }

    public getAlbumEntry(albumId: string, albumEntryId: string): Promise<[AlbumEntryData, Set<string>] | undefined> {
        this.lastAccessTime.set(albumId, Date.now());
        return this.transaction('r',
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable,
            async () => {
                const key = [albumId, albumEntryId];
                const [entry, addKeywords, removeKeywords] = await Promise.all([
                    this.albumEntryDataTable.get(key),
                    this.whereAddPendingKeyword.equals(key).toArray(),
                    this.whereRemovePendingKeyword.equals(key).toArray()]);
                if (entry === undefined) {
                    return undefined;
                }
                const keywords = new Set(entry.keywords);
                addKeywords.forEach(e => keywords.add(e.keyword));
                removeKeywords.forEach(e => keywords.delete(e.keyword));
                return [entry, keywords];
            }
        );
    }

    public readImage(albumId: string, albumEntryId: string, minSize: number): Promise<ImageBlob | undefined> {
        return this.transaction('r', this.imagesTable, async () => {
            const storedData = await this.imagesTable.get([albumId, albumEntryId]);
            if (storedData !== undefined && storedData.mediaSize >= minSize) {
                return storedData;
            }
            return undefined;
        });
    }

    public async storeImage(data: ImageBlob): Promise<void> {
        await this.cleanupSemaphore.acquire();
        try {
            if (navigator.storage && navigator.storage.estimate) {
                const estimation = await navigator.storage.estimate();
                const quota = estimation.quota;
                const usage = estimation.usage;
                if (usage + data.data.size * 1.5 > quota - 10 * 1000 * 1000) {
                    // console.log('storage limit reached');
                    // console.log(`Quota: ${quota}`);
                    // console.log(`Usage: ${usage}`);
                    await this.cleanupOldestAlbum();
                }
            }
        } finally {
            this.cleanupSemaphore.release();
        }
        return this.transaction('rw', this.imagesTable, async () => {
            const oldEntry = await this.imagesTable.get([data.albumId, data.albumEntryId]);
            if (oldEntry === undefined || oldEntry.mediaSize < data.mediaSize) {
                await this.imagesTable.put(data);
            }
        }).catch(async ex => {
            console.log(ex);
            if (isQuotaExceeded(ex)) {
                // QuotaExceededError may occur as the inner error of an AbortError
                console.error('QuotaExceeded cleanup oldest album');
                await this.cleanupOldestAlbum();
                return this.storeImage(data);
            }
            throw ex;
        });
    }


    private async cleanupOldestAlbum(): Promise<void> {
        return this.transaction('rw', this.albumDataTable, this.albumEntryDataTable, this.imagesTable, async () => {
            const emptyAlbums: string[] = [];
            const albumsWithData: string[] = [];
            await this.albumDataTable.each(a => {
                if (a.albumEntryVersion === undefined) {
                    emptyAlbums.push(a.id);
                } else {
                    albumsWithData.push(a.id);
                }
            });
            // cleanup orphaned entries
            const deleteOrphanCount = await this.albumEntryDataTable.where('albumId').anyOf(emptyAlbums).delete() +
                await this.imagesTable.where('albumId').anyOf(emptyAlbums).delete();
            if (deleteOrphanCount > 0) {
                return;
            }
            let oldestEntryTime = Number.MAX_SAFE_INTEGER;
            let oldestEntryKey;
            albumsWithData.forEach(key => {
                const time = this.lastAccessTime.get(key) || -1;
                if (time < oldestEntryTime) {
                    oldestEntryKey = key;
                    oldestEntryTime = time;
                }
            });
            const nextFoundKey = oldestEntryKey;
            if (nextFoundKey === undefined) {
                return;
            }
            // remove images
            const removedImages = await this.imagesTable.where('albumId').equals(nextFoundKey).delete();
            if (removedImages > 0) {
                return;
            }
            const album = await this.albumDataTable.get(nextFoundKey);
            // invalidate content
            if (album !== undefined) {
                album.albumEntryVersion = undefined;
                await this.albumDataTable.put(album);
            }
            // remove entries
            await this.albumEntryDataTable.where('albumId').equals(nextFoundKey).delete();
        }).catch(ex => {
            console.error('cannot cleanup data', ex);
        });

    }
}

function isQuotaExceeded(ex: any) {
    return (ex.name === 'QuotaExceededError') ||
        (ex.inner && ex.inner.name === 'QuotaExceededError');

}
