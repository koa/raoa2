import {Injectable} from '@angular/core';

@Injectable({
    providedIn: 'root'
})
export class MediaResolverService {
    private lastAlbumId: string;
    private bestLoadedImage = {};

    constructor() {
    }

    public lookupImage(albumId: string, entryId: string, maxLength: number) {
        if (albumId !== this.lastAlbumId) {
            this.bestLoadedImage = {};
        }
        const loadedSize = this.bestLoadedImage[entryId];
        const nextStepMaxLength = findNextStep(maxLength);
        let selectedMaxLength: number;
        if (loadedSize !== null && loadedSize > nextStepMaxLength) {
            selectedMaxLength = loadedSize;
        } else {
            selectedMaxLength = nextStepMaxLength;
            this.bestLoadedImage[entryId] = nextStepMaxLength;
        }
        return '/rest/album/' + albumId + '/' + entryId + '/thumbnail?maxLength=' + selectedMaxLength;
    }
}

function findNextStep(maxLength: number): number {
    if (maxLength > 800) {
        return 1600;
    }
    if (maxLength > 400) {
        return 800;
    }
    if (maxLength > 200) {
        return 400;
    }
    if (maxLength > 100) {
        return 200;
    }
    if (maxLength > 50) {
        return 100;
    }
    return 50;
}
