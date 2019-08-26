import {Injectable} from '@angular/core';
import {HttpLink} from 'apollo-angular-link-http';
import {InMemoryCache} from 'apollo-cache-inmemory';
import {ApolloClientOptions} from 'apollo-client';
import {Apollo, ApolloBase} from "apollo-angular";

export function createApollo(httpLink: HttpLink, method: string): ApolloClientOptions<any> {
  const host = window.location.host;
  const protocol = window.location.protocol;
  const uri = protocol + '//' + host + '/graphql';

  return {
    link: httpLink.create({uri, method}),
    cache: new InMemoryCache(),
  };
}

@Injectable({
  providedIn: 'root'
})
export class ApolloService {

  constructor(private apollo: Apollo, httpLink: HttpLink) {
    const host = window.location.host;
    const protocol = window.location.protocol;
    const uri = protocol + '//' + host + '/graphql';

    apollo.create({
      link: httpLink.create({uri, method: 'GET'}),
      cache: new InMemoryCache(),
    }, 'query');
    apollo.create({
      link: httpLink.create({uri, method: 'POST'}),
      cache: new InMemoryCache(),
    }, 'update');
  }

  public query(): ApolloBase<any> {
    return this.apollo.use('query');
  }

  public update(): ApolloBase<any> {
    return this.apollo.use('update');
  }
}
